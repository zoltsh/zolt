package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import sh.zolt.project.BuildSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

final class WorkspaceFileSnapshot {
    private final Map<Path, String> hashes = new LinkedHashMap<>();
    private long bytesHashed;

    TreeDigest javaSources(Path projectDirectory, List<String> roots) {
        return roots(
                projectDirectory,
                roots,
                path -> path.getFileName().toString().endsWith(".java"));
    }

    TreeDigest resources(Path projectDirectory, List<String> roots) {
        return roots(
                projectDirectory,
                roots,
                path -> !path.getFileName().toString().endsWith(".java"));
    }

    TreeDigest paths(Path projectDirectory, List<Path> inputs) {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        List<Path> files = new ArrayList<>();
        for (Path input : inputs) {
            Path normalized = input.toAbsolutePath().normalize();
            if (Files.isDirectory(normalized)) {
                files.addAll(walk(normalized, ignored -> true));
            } else if (Files.isRegularFile(normalized)) {
                files.add(normalized);
            }
        }
        return digest(projectRoot, files);
    }

    String pathHash(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            return digest(normalized, walk(normalized, ignored -> true)).digest();
        }
        if (!Files.isRegularFile(normalized)) {
            return "missing";
        }
        return hashes.computeIfAbsent(normalized, this::readHash);
    }

    boolean resourceOutputsCurrent(
            Path projectDirectory,
            BuildSettings build) {
        return copiedResourcesCurrent(
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
    boolean testResourceOutputsCurrent(
            Path projectDirectory,
            BuildSettings build) {
        return copiedResourcesCurrent(
                projectDirectory,
                build.testResourceRoots(),
                build.testOutput(),
                build.resourceFiltering().enabled() && build.resourceFiltering().testEnabled());
    }

    private boolean copiedResourcesCurrent(
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
            for (Path input : walk(
                    root,
                    path -> !path.getFileName().toString().endsWith(".java"))) {
                Path candidate = output.resolve(root.relativize(input)).normalize();
                if (!Files.isRegularFile(candidate)
                        || !pathHash(input).equals(pathHash(candidate))) {
                    return false;
                }
            }
        }
        return true;
    }

    long bytesHashed() {
        return bytesHashed;
    }

    int filesHashed() {
        return hashes.size();
    }

    private TreeDigest roots(
            Path projectDirectory,
            List<String> configuredRoots,
            Predicate<Path> included) {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        List<Path> files = configuredRoots.stream()
                .map(root -> confined(projectRoot, root))
                .flatMap(root -> walk(root, included).stream())
                .distinct()
                .toList();
        return digest(projectRoot, files);
    }

    private TreeDigest digest(Path relativeRoot, List<Path> files) {
        StringBuilder content = new StringBuilder();
        files.stream()
                .distinct()
                .sorted()
                .forEach(path -> content
                        .append(display(relativeRoot, path))
                        .append('|')
                        .append(pathHash(path))
                        .append('\n'));
        return new TreeDigest(
                WorkspaceHash.text(content.toString()),
                files.size());
    }

    private List<Path> walk(Path root, Predicate<Path> included) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(included)
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not snapshot workspace files under " + root + ".",
                    exception);
        }
    }

    private String readHash(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            bytesHashed += bytes.length;
            return WorkspaceHash.bytes(bytes);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not hash workspace input " + path + ".",
                    exception);
        }
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

    record TreeDigest(String digest, int fileCount) {
    }
}
