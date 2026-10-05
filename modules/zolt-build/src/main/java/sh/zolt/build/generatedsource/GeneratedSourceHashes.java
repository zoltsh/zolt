package sh.zolt.build.generatedsource;

import sh.zolt.build.BuildException;
import sh.zolt.build.packageplan.PackageInputFingerprinting;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Shared SHA-256 content-hash primitives for generated-source producer inputs and owned outputs. */
final class GeneratedSourceHashes {
    private GeneratedSourceHashes() {
    }

    static String fileHash(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            return directoryHash(normalized);
        }
        if (!Files.isRegularFile(normalized)) {
            return "missing";
        }
        try {
            return sha256(Files.readAllBytes(normalized));
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not fingerprint generated-source input " + normalized + ". Check that it is readable.",
                    exception);
        }
    }

    static String classpathHash(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return Files.isDirectory(normalized)
                ? PackageInputFingerprinting
                        .applicationOutputFingerprint(normalized)
                : fileHash(normalized);
    }

    static String directoryHash(Path directory) {
        return directoryHash(directory, Set.of());
    }

    static String directoryHash(Path directory, Set<Path> excludedFiles) {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        Set<Path> normalizedExclusions = excludedFiles.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());
        try (Stream<Path> paths = Files.walk(normalizedDirectory)) {
            StringBuilder content = new StringBuilder();
            paths.filter(Files::isRegularFile)
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(path -> !normalizedExclusions.contains(path))
                    .sorted()
                    .forEach(path -> content
                            .append(normalizedDirectory.relativize(path).toString().replace('\\', '/'))
                            .append('|')
                            .append(fileHash(path))
                            .append('\n'));
            return sha256(content.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not fingerprint generated-source directory "
                            + normalizedDirectory
                            + ". Check that it is readable.",
                    exception);
        }
    }

    static String relative(Path projectRoot, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(projectRoot)) {
            return projectRoot.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString().replace('\\', '/');
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new BuildException(
                    "Could not compute generated-source fingerprint because SHA-256 is unavailable.", exception);
        }
    }
}
