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
import java.util.stream.Stream;

/**
 * Reusable SHA-256 content-hash primitives for generated-source producer caches. Extracted so the
 * exec producer cache can share the exact hashing the OpenAPI cache pioneered without disturbing the
 * OpenAPI cache's byte-for-byte behavior.
 */
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
        try (Stream<Path> paths = Files.walk(directory)) {
            StringBuilder content = new StringBuilder();
            paths.filter(Files::isRegularFile)
                    .map(Path::normalize)
                    .sorted()
                    .forEach(path -> content
                            .append(directory.relativize(path).toString().replace('\\', '/'))
                            .append('|')
                            .append(fileHash(path))
                            .append('\n'));
            return sha256(content.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not fingerprint generated-source directory " + directory + ". Check that it is readable.",
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
