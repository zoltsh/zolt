package sh.zolt.build.fingerprint;

import sh.zolt.build.output.CompilerOutputInventory;
import java.util.Set;
import java.util.TreeSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class BuildFingerprintExpectedClasses {
    List<String> entries(Path projectRoot, Path outputDirectory) {
        return entries(projectRoot, outputDirectory, storedOverlays(outputDirectory));
    }

    List<String> entries(Path projectRoot, Path outputDirectory, Set<Path> overlays) {
        return compilerOutputs(outputDirectory, overlays).stream()
                .map(path -> relative(projectRoot, path)).toList();
    }

    List<String> hashes(Path projectRoot, Path outputDirectory) {
        return hashes(projectRoot, outputDirectory, storedOverlays(outputDirectory));
    }

    List<String> hashes(Path projectRoot, Path outputDirectory, Set<Path> overlays) {
        var original = CompilerOutputInventory.read(outputDirectory)
                .map(CompilerOutputInventory.Snapshot::files).orElse(java.util.Map.of());
        Path root = outputDirectory.toAbsolutePath().normalize();
        return compilerOutputs(root, overlays).stream().map(path -> {
            Path relative = root.relativize(path);
            String hash = overlays.contains(relative) && original.containsKey(relative)
                    ? original.get(relative) : CompilerOutputInventory.fileHash(path);
            return relative(projectRoot, path) + "|" + hash;
        }).toList();
    }

    private static Set<Path> storedOverlays(Path output) {
        return CompilerOutputInventory.read(output).map(CompilerOutputInventory.Snapshot::overlays).orElse(Set.of());
    }

    List<Path> missing(Path projectRoot, Path output, Set<Path> overlays, String fingerprint) {
        List<Path> missing = new ArrayList<>();
        boolean expectedClassesSection = false;
        for (String line : fingerprint.lines().toList()) {
            if (line.startsWith("[") && line.endsWith("]")) {
                expectedClassesSection = "[expectedClasses]".equals(line);
                continue;
            }
            if (expectedClassesSection && !line.isBlank()) {
                Path recorded = Path.of(line);
                Path expectedClass = recorded.isAbsolute()
                        ? recorded.normalize()
                        : projectRoot.resolve(recorded).normalize();
                if (!overlays.contains(output.toAbsolutePath().normalize().relativize(expectedClass))
                        && !Files.isRegularFile(expectedClass)) {
                    missing.add(expectedClass);
                }
            }
        }
        return List.copyOf(missing);
    }

    boolean recordedOutputsCurrent(Path projectRoot, Path outputDirectory, String fingerprint) {
        return CompilerOutputInventory.read(outputDirectory).isPresent()
                && fingerprint.lines().anyMatch("[expectedClasses]"::equals)
                && fingerprint.lines().anyMatch("[compilerOutputHashes]"::equals)
                && recordedSection(fingerprint, "expectedClasses")
                        .equals(new java.util.TreeSet<>(entries(projectRoot, outputDirectory)))
                && recordedSection(fingerprint, "compilerOutputHashes")
                        .equals(new java.util.TreeSet<>(hashes(projectRoot, outputDirectory)));
    }

    private static java.util.Set<String> recordedSection(String fingerprint, String name) {
        var recorded = new java.util.TreeSet<String>();
        boolean included = false;
        for (String line : fingerprint.lines().toList()) {
            if (line.startsWith("[") && line.endsWith("]")) {
                included = ("[" + name + "]").equals(line);
            } else if (included && !line.isBlank()) {
                recorded.add(line);
            }
        }
        return recorded;
    }

    private static List<Path> compilerOutputs(Path outputDirectory, Set<Path> overlays) {
        Path root = outputDirectory.toAbsolutePath().normalize();
        var inventory = CompilerOutputInventory.read(root);
        Set<Path> owned = new TreeSet<>();
        inventory.ifPresent(value -> owned.addAll(value.files().keySet()));
        Set<Path> masked = new java.util.HashSet<>(overlays);
        inventory.ifPresent(value -> masked.addAll(value.overlays()));
        for (Path path : CompilerOutputInventory.outputFiles(root)) {
            if (!masked.contains(path)) {
                owned.add(path);
            }
        }
        return owned.stream().map(root::resolve).toList();
    }

    private static String relative(Path projectRoot, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(projectRoot)) {
            return projectRoot.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString().replace('\\', '/');
    }
}
