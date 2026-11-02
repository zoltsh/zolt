package sh.zolt.workspace.state;

import sh.zolt.build.BuildException;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectPaths;
import sh.zolt.project.ProjectPathException;
import sh.zolt.workspace.state.WorkspaceSnapshotFiles.Entry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The workspace's view of its own input files: one stat and content read per file per command.
 * Persisted timestamps are observations, not proof that mutable content is unchanged.
 *
 * <p>Directory walks carry each file's {@link java.nio.file.attribute.BasicFileAttributes} straight from the walk into the
 * hasher, so the size, modification time, and file key the comparison needs cost nothing beyond the
 * traversal that had to happen anyway. Regular-file digests retain sorted
 * {@code relativePath|contentHash} lines. Accepted file links additionally include their confined,
 * member-relative target identity so retargeting remains visible even when bytes match.
 */
public final class WorkspaceFileSnapshot {
    private static final Set<String> COMPILER_STATE_FILES = Set.of(
            ".zolt-build-main.fingerprint",
            ".zolt-build-main.fingerprint.state",
            ".zolt-build-test.fingerprint",
            ".zolt-build-test.fingerprint.state",
            ".zolt-incremental-main.state",
            ".zolt-incremental-test.state",
            ".zolt-compiler-outputs.state",
            ".zolt-resources.state");

    private final WorkspaceFileHasher hasher;

    public WorkspaceFileSnapshot(Path workspaceRoot, WorkspaceFileState previous, boolean paranoid) {
        this.hasher = new WorkspaceFileHasher(workspaceRoot, previous, paranoid);
    }

    public TreeDigest javaSources(
            String member,
            WorkspaceFileKind kind,
            Path projectDirectory,
            List<String> roots) {
        return roots(member, kind, projectDirectory, roots, WorkspaceFileSnapshot::java);
    }

    public TreeDigest mainSources(
            String member,
            WorkspaceFileKind kind,
            Path projectDirectory,
            List<String> roots) {
        return roots(member, kind, projectDirectory, roots, WorkspaceFileSnapshot::source);
    }

    public TreeDigest testSources(
            String member,
            WorkspaceFileKind kind,
            Path projectDirectory,
            List<String> javaRoots,
            List<String> groovyRoots) {
        return testSources(member, kind, projectDirectory, javaRoots, groovyRoots, List.of());
    }

    public TreeDigest testSources(
            String member,
            WorkspaceFileKind kind,
            Path projectDirectory,
            List<String> javaRoots,
            List<String> groovyRoots,
            List<String> kotlinRoots) {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        List<Entry> files = new ArrayList<>();
        addRootFiles(projectRoot, javaRoots, WorkspaceFileSnapshot::javaOrKotlin, files);
        addRootFiles(projectRoot, groovyRoots, WorkspaceFileSnapshot::groovyOrKotlin, files);
        addRootFiles(projectRoot, kotlinRoots, WorkspaceFileSnapshot::kotlin, files);
        hasher.sweep(member, kind);
        return digest(member, kind, projectRoot, files);
    }

    public TreeDigest resources(
            String member,
            WorkspaceFileKind kind,
            Path projectDirectory,
            List<String> roots) {
        return roots(member, kind, projectDirectory, roots, path -> !source(path));
    }

    /** Everything under one directory, used for the generated sources a member's processors emit. */
    public TreeDigest tree(String member, WorkspaceFileKind kind, Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        hasher.sweep(member, kind);
        return digest(member, kind, normalized, walk(normalized, ignored -> true));
    }

    /** Processor classes and resources are executable inputs; exclude only local compiler state. */
    public TreeDigest processorOutput(String member, Path output) {
        Path root = output.toAbsolutePath().normalize();
        WorkspaceFileKind kind = WorkspaceFileKind.PROCESSOR_OUTPUT;
        hasher.sweep(member, kind);
        return digest(member, kind, root, walk(root,
                path -> !COMPILER_STATE_FILES.contains(root.relativize(path).toString())));
    }

    public TreeDigest paths(
            String member,
            WorkspaceFileKind kind,
            Path projectDirectory,
            List<Path> inputs) {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        List<Entry> files = new ArrayList<>();
        for (Path input : inputs) {
            Path normalized = input.toAbsolutePath().normalize();
            if (Files.isDirectory(normalized)) {
                files.addAll(walk(normalized, ignored -> true));
            } else if (Files.isRegularFile(normalized)) {
                files.add(new Entry(normalized, null));
            }
        }
        hasher.sweep(member, kind);
        return digest(member, kind, projectRoot, files);
    }

    public String pathHash(String member, WorkspaceFileKind kind, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            return digest(member, kind, normalized, walk(normalized, ignored -> true)).digest();
        }
        return hasher.hash(normalized, kind, member);
    }

    public boolean resourceOutputsCurrent(String member, Path projectDirectory, BuildSettings build) {
        return copiedResourcesCurrent(
                member,
                WorkspaceFileKind.MAIN_RESOURCE,
                projectDirectory,
                build.resourceRoots(),
                build.output(),
                build.resourceFiltering().enabled());
    }

    /**
     * The test-lane twin of {@link #resourceOutputsCurrent}. Test resources are copied into the test
     * output by the member's test compile, and filtering opts in per lane exactly as the copier reads
     * it, so the test lane is declared stale for filtering only when filtering would rewrite the test
     * bytes.
     */
    public boolean testResourceOutputsCurrent(String member, Path projectDirectory, BuildSettings build) {
        return copiedResourcesCurrent(
                member,
                WorkspaceFileKind.TEST_RESOURCE,
                projectDirectory,
                build.testResourceRoots(),
                build.testOutput(),
                build.resourceFiltering().enabled() && build.resourceFiltering().testEnabled());
    }

    /**
     * Drops everything this command recorded about one member's source set, so the next read of it
     * goes back to the filesystem. Callers use this after a build has rewritten what it observed.
     */
    public void forget(String member, WorkspaceFileKind kind) {
        hasher.forget(member, kind);
    }

    /** The table to persist, unfenced; the store stamps a fence onto it when it commits the state. */
    public WorkspaceFileState state() {
        return hasher.state();
    }

    public long bytesHashed() {
        return hasher.bytesHashed();
    }

    public int filesHashed() {
        return hasher.filesHashed();
    }

    public int filesStatted() {
        return hasher.filesStatted();
    }

    public int filesReused() {
        return hasher.filesReused();
    }

    private boolean copiedResourcesCurrent(
            String member,
            WorkspaceFileKind kind,
            Path projectDirectory,
            List<String> resourceRoots,
            String outputRoot,
            boolean filtered) {
        if (filtered) {
            return false;
        }
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        Path output = confined(projectRoot, outputRoot);
        for (String configuredRoot : resourceRoots) {
            Path root = confined(projectRoot, configuredRoot);
            for (Entry input : inputFiles(projectRoot, root, path -> !source(path))) {
                Path candidate = output.resolve(root.relativize(input.path())).normalize();
                String copied = hasher.hash(candidate, WorkspaceFileKind.OUTPUT_RESOURCE, member);
                if (!hasher.hash(input.path(), input.attributes(), kind, member).equals(copied)) {
                    return false;
                }
            }
        }
        return true;
    }

    private TreeDigest roots(
            String member,
            WorkspaceFileKind kind,
            Path projectDirectory,
            List<String> configuredRoots,
            Predicate<Path> included) {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        List<Entry> files = configuredRoots.stream()
                .map(root -> confined(projectRoot, root))
                .flatMap(root -> inputFiles(projectRoot, root, included).stream())
                .toList();
        hasher.sweep(member, kind);
        return digest(member, kind, projectRoot, files);
    }

    private static void addRootFiles(
            Path projectRoot,
            List<String> configuredRoots,
            Predicate<Path> included,
            List<Entry> files) {
        configuredRoots.stream()
                .map(root -> confined(projectRoot, root))
                .flatMap(root -> inputFiles(projectRoot, root, included).stream())
                .forEach(files::add);
    }

    private TreeDigest digest(
            String member,
            WorkspaceFileKind kind,
            Path relativeRoot,
            List<Entry> files) {
        StringBuilder content = new StringBuilder();
        Path previous = null;
        int counted = 0;
        for (Entry entry : files.stream().sorted(Comparator.comparing(Entry::path)).toList()) {
            if (entry.path().equals(previous)) {
                continue;
            }
            previous = entry.path();
            counted++;
            content.append(display(relativeRoot, entry.path()))
                    .append('|')
                    .append(hasher.hash(entry.path(), entry.attributes(), kind, member));
            if (entry.linkTarget() != null) {
                content.append("|target:").append(entry.linkTarget());
            }
            content.append('\n');
        }
        return new TreeDigest(WorkspaceHash.text(content.toString()), counted);
    }

    private static List<Entry> walk(Path root, Predicate<Path> included) {
        return WorkspaceSnapshotFiles.walk(root, included, null);
    }

    private static List<Entry> inputFiles(Path projectRoot, Path root, Predicate<Path> included) {
        return WorkspaceSnapshotFiles.walk(root, included, projectRoot);
    }

    private static boolean java(Path path) {
        return path.getFileName().toString().endsWith(".java");
    }

    private static boolean groovy(Path path) {
        return path.getFileName().toString().endsWith(".groovy");
    }

    private static boolean kotlin(Path path) {
        return path.getFileName().toString().endsWith(".kt");
    }

    private static boolean javaOrKotlin(Path path) {
        return java(path) || kotlin(path);
    }

    private static boolean groovyOrKotlin(Path path) {
        return groovy(path) || kotlin(path);
    }

    private static boolean source(Path path) {
        return java(path) || groovy(path) || kotlin(path);
    }

    private static Path confined(Path projectRoot, String configuredPath) {
        try {
            return ProjectPaths.existingRoot(projectRoot, "workspace input", configuredPath);
        } catch (ProjectPathException exception) {
            throw new BuildException(exception.getMessage(), exception);
        }
    }

    private static String display(Path root, Path path) {
        return path.startsWith(root)
                ? root.relativize(path).toString().replace('\\', '/')
                : path.toString().replace('\\', '/');
    }

    public record TreeDigest(String digest, int fileCount) {
    }
}
