package sh.zolt.build.fingerprint;

import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

final class BuildFingerprintExpectedClasses {
    List<String> entries(
            Path projectRoot,
            List<String> sourceRoots,
            List<Path> sources,
            Path outputDirectory) {
        List<Path> outputs = hasKotlinSource(sources)
                ? compilerOutputs(outputDirectory)
                : files(projectRoot, sourceRoots, sources, outputDirectory);
        return outputs.stream()
                .filter(path -> !isPackageInfo(path) || Files.isRegularFile(path))
                .sorted()
                .map(path -> relative(projectRoot, path))
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

    boolean recordedOutputsCurrent(Path projectRoot, String fingerprint) {
        return fingerprint.lines().anyMatch("[expectedClasses]"::equals)
                && missing(projectRoot, fingerprint).isEmpty();
    }

    List<Path> files(
            Path projectRoot,
            List<String> sourceRoots,
            List<Path> sources,
            Path outputDirectory) {
        List<Path> roots = sourceRoots.stream()
                .map(root -> projectRoot.resolve(root).normalize())
                .toList();
        return sources.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .map(path -> classFile(sourceRootFor(roots, path), path, outputDirectory))
                .flatMap(Optional::stream)
                .sorted()
                .toList();
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

    private static boolean hasKotlinSource(List<Path> sources) {
        return sources.stream().anyMatch(path -> path.getFileName().toString().endsWith(".kt"));
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

    private static Optional<Path> classFile(Optional<Path> sourceRoot, Path source, Path outputDirectory) {
        if (sourceRoot.isEmpty()) {
            return Optional.empty();
        }
        Path relative = sourceRoot.orElseThrow().relativize(source);
        String fileName = relative.getFileName().toString();
        String extension;
        if (fileName.endsWith(".java")) {
            extension = ".java";
        } else if (fileName.endsWith(".groovy")) {
            extension = ".groovy";
        } else {
            return Optional.empty();
        }
        Path classRelative = relative.getParent() == null
                ? Path.of(fileName.substring(0, fileName.length() - extension.length()) + ".class")
                : relative.getParent().resolve(fileName.substring(0, fileName.length() - extension.length()) + ".class");
        return Optional.of(outputDirectory.resolve(classRelative).normalize());
    }

    private static boolean isPackageInfo(Path classFile) {
        Path fileName = classFile.getFileName();
        return fileName != null && "package-info.class".equals(fileName.toString());
    }

    private static Optional<Path> sourceRootFor(List<Path> sourceRoots, Path source) {
        return sourceRoots.stream()
                .filter(source::startsWith)
                .max(Comparator.comparingInt(Path::getNameCount));
    }

    private static String relative(Path projectRoot, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(projectRoot)) {
            return projectRoot.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString().replace('\\', '/');
    }
}
