package sh.zolt.workspace.state;

import sh.zolt.build.BuildException;
import sh.zolt.project.BuildSettings;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The workspace's view of its own input files: one stat per file per command, and a content read
 * only for the files whose stat no longer matches what {@link WorkspaceFileHasher} recorded.
 *
 * <p>Directory walks carry each file's {@link BasicFileAttributes} straight from the walk into the
 * hasher, so the size, modification time, and file key the comparison needs cost nothing beyond the
 * traversal that had to happen anyway. Digests keep their previous shape — a sorted list of
 * {@code relativePath|contentHash} lines — so a command that reuses every hash produces byte-identical
 * keys to one that re-reads every file.
 */
public final class WorkspaceFileSnapshot {
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

    public TreeDigest resources(
            String member,
            WorkspaceFileKind kind,
            Path projectDirectory,
            List<String> roots) {
        return roots(member, kind, projectDirectory, roots, path -> !java(path));
    }

    /** Everything under one directory, used for the generated sources a member's processors emit. */
    public TreeDigest tree(String member, WorkspaceFileKind kind, Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        hasher.sweep(member, kind);
        return digest(member, kind, normalized, walk(normalized, ignored -> true));
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
            for (Entry input : walk(root, path -> !java(path))) {
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
                .flatMap(root -> walk(root, included).stream())
                .toList();
        hasher.sweep(member, kind);
        return digest(member, kind, projectRoot, files);
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
                    .append(hasher.hash(entry.path(), entry.attributes(), kind, member))
                    .append('\n');
        }
        return new TreeDigest(WorkspaceHash.text(content.toString()), counted);
    }

    private static List<Entry> walk(Path root, Predicate<Path> included) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    Path normalized = file.toAbsolutePath().normalize();
                    if (attributes.isRegularFile() && included.test(normalized)) {
                        entries.add(new Entry(normalized, attributes));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not snapshot workspace files under " + root + ".",
                    exception);
        }
        return entries;
    }

    private static boolean java(Path path) {
        return path.getFileName().toString().endsWith(".java");
    }

    private static Path confined(Path projectRoot, String configuredPath) {
        Path configured = Path.of(configuredPath);
        Path resolved = projectRoot.resolve(configured).normalize();
        if (configured.isAbsolute() || !resolved.startsWith(projectRoot)) {
            throw new BuildException(
                    "Workspace input path escapes its member: " + configuredPath);
        }
        return resolved;
    }

    private static String display(Path root, Path path) {
        return path.startsWith(root)
                ? root.relativize(path).toString().replace('\\', '/')
                : path.toString().replace('\\', '/');
    }

    private record Entry(Path path, BasicFileAttributes attributes) {
    }

    public record TreeDigest(String digest, int fileCount) {
    }
}
