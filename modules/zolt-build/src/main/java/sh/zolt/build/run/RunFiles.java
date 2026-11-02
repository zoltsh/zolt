package sh.zolt.build.run;

import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable copies of mutable build outputs used by a long-lived application process.
 */
public final class RunFiles implements AutoCloseable {
    private final Path root;
    private final Map<Path, Path> captured = new LinkedHashMap<>();
    private int nextEntry;

    private RunFiles(Path root) {
        this.root = root;
    }

    public static RunFiles create(Path projectRoot) {
        try {
            Path root = projectRoot.toRealPath().resolve(".zolt/run").resolve(UUID.randomUUID().toString());
            Files.createDirectories(root);
            return new RunFiles(root);
        } catch (IOException exception) {
            throw new BuildException("Could not create immutable application run snapshot for " + projectRoot + ".",
                    exception);
        }
    }

    public Path root() {
        return root;
    }

    public Path capture(Path source) {
        Path normalized = source.toAbsolutePath().normalize();
        Path existing = captured.get(normalized);
        if (existing != null) {
            return existing;
        }
        Path target = root.resolve(
                "%04d-%s".formatted(nextEntry++, safeName(normalized)));
        copy(normalized, target);
        captured.put(normalized, target);
        return target;
    }

    public void captureProjectEntries(Path projectRoot, List<Path> entries) {
        try {
            Path project = projectRoot.toRealPath();
            for (Path entry : entries) {
                if (entry.toRealPath().startsWith(project)) {
                    capture(entry);
                }
            }
        } catch (IOException exception) {
            throw new BuildException("Could not identify application run inputs under " + projectRoot + ".", exception);
        }
    }

    public List<Path> remap(List<Path> entries) {
        return entries.stream()
                .map(this::remap)
                .toList();
    }

    public Path remap(Path entry) {
        Path normalized = entry.toAbsolutePath().normalize();
        for (Map.Entry<Path, Path> mapping : captured.entrySet()) {
            if (normalized.startsWith(mapping.getKey())) {
                return mapping.getValue().resolve(
                        mapping.getKey().relativize(normalized));
            }
        }
        return normalized;
    }

    @Override
    public void close() {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not remove application run snapshot at "
                            + root
                            + ".",
                    exception);
        }
    }

    private static void copy(
            Path source,
            Path target) {
        if (!Files.exists(source)) {
            throw new BuildException(
                    "Could not snapshot missing application run input "
                            + source
                            + ".");
        }
        try {
            if (Files.isDirectory(source)) {
                try (var paths = Files.walk(source)) {
                    for (Path path : paths.toList()) {
                        Path destination = target.resolve(source.relativize(path));
                        if (Files.isDirectory(path)) {
                            Files.createDirectories(destination);
                        } else {
                            Files.createDirectories(destination.getParent());
                            Files.copy(
                                    path,
                                    destination,
                                    StandardCopyOption.COPY_ATTRIBUTES);
                        }
                    }
                }
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(
                        source,
                        target,
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not copy application run input "
                            + source
                            + " into immutable snapshot "
                            + target
                            + ".",
                    exception);
        }
    }

    private static String safeName(Path path) {
        Path fileName = path.getFileName();
        String value = fileName == null ? "entry" : fileName.toString();
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "entry" : safe;
    }
}
