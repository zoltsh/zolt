package sh.zolt.build.packaging;

import sh.zolt.build.packageplan.PackageInputFingerprinting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

final class PackageSupplementalArtifactFiles {
    private PackageSupplementalArtifactFiles() {
    }

    static List<Path> sourceArchiveFiles(Path sourceRoot) throws IOException {
        return sourceFiles(sourceRoot, PackageSupplementalArtifactFiles::isMainSource);
    }

    static List<Path> javadocSourceFiles(Path sourceRoot) throws IOException {
        return sourceFiles(sourceRoot, path -> path.getFileName().toString().endsWith(".java"));
    }

    private static List<Path> sourceFiles(
            Path sourceRoot,
            Predicate<Path> include) throws IOException {
        if (!Files.isDirectory(sourceRoot)) {
            return List.of();
        }
        try (var stream = Files.walk(sourceRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(include)
                    .sorted(Comparator.comparing(path -> entryName(sourceRoot, path)))
                    .toList();
        }
    }

    private static boolean isMainSource(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".java") || fileName.endsWith(".groovy");
    }

    static List<Path> regularFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> entryName(root, path)))
                    .toList();
        }
    }

    static List<Path> compiledFiles(Path outputDirectory) throws IOException {
        return PackageInputFingerprinting.applicationFiles(outputDirectory);
    }

    static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.delete(path);
            }
        }
    }

    private static String entryName(Path outputDirectory, Path file) {
        return outputDirectory.relativize(file).normalize().toString().replace('\\', '/');
    }
}
