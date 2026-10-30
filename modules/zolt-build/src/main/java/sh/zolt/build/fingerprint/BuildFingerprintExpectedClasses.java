package sh.zolt.build.fingerprint;

import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

final class BuildFingerprintExpectedClasses {
    List<String> entries(
            Path projectRoot,
            Path outputDirectory) {
        // Java also emits nested, anonymous, package-private, and processor-generated classes.
        // Source filenames cannot enumerate those outputs (or even predict the declared package).
        List<Path> outputs = compilerOutputs(outputDirectory);
        return outputs.stream()
                .sorted()
                .map(path -> relative(projectRoot, path))
                .toList();
    }

    List<String> hashes(Path projectRoot, Path outputDirectory) {
        BuildFingerprintFileHasher hasher = new BuildFingerprintFileHasher();
        return compilerOutputs(outputDirectory).stream()
                .map(path -> relative(projectRoot, path) + "|" + hasher.fileHash(path, null, null))
                .toList();
    }

    List<Path> missing(Path projectRoot, String fingerprint) {
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
                if (!Files.isRegularFile(expectedClass)) {
                    missing.add(expectedClass);
                }
            }
        }
        return List.copyOf(missing);
    }

    boolean recordedOutputsCurrent(Path projectRoot, Path outputDirectory, String fingerprint) {
        return fingerprint.lines().anyMatch("[expectedClasses]"::equals)
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

    private static List<Path> compilerOutputs(Path outputDirectory) {
        Path outputRoot = outputDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(outputRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(outputRoot)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(Path::normalize)
                    .filter(path -> isClassFile(path) || isKotlinModule(outputRoot, path))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not inventory compiler outputs under "
                            + outputRoot
                            + ". Check that the directory is readable.",
                    exception);
        }
    }

    private static boolean isClassFile(Path path) {
        return path.getFileName().toString().endsWith(".class");
    }

    private static boolean isKotlinModule(Path outputRoot, Path path) {
        Path relative = outputRoot.relativize(path);
        return relative.getNameCount() == 2
                && "META-INF".equals(relative.getName(0).toString())
                && relative.getFileName().toString().endsWith(".kotlin_module");
    }

    private static String relative(Path projectRoot, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(projectRoot)) {
            return projectRoot.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString().replace('\\', '/');
    }
}
