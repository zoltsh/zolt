package sh.zolt.build.incremental;

import sh.zolt.build.BuildException;
import sh.zolt.build.lockfile.VerifiedArtifactHashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

final class IncrementalCompileInputHasher {
    private static final Set<String> LOCAL_COMPILE_METADATA = Set.of(
            ".zolt-build-main.fingerprint",
            ".zolt-build-main.fingerprint.state",
            ".zolt-build-test.fingerprint",
            ".zolt-build-test.fingerprint.state",
            IncrementalCompileState.MAIN_FILE_NAME,
            IncrementalCompileState.TEST_FILE_NAME);

    private IncrementalCompileInputHasher() {
    }

    static String hashText(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static String hash(Path path) {
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
                    "Could not hash incremental compile input "
                            + normalized
                            + ". Check that it is readable.",
                    exception);
        }
    }

    static IncrementalCompileState.ClasspathEntry classpathEntry(Path path) {
        return classpathEntry(path, VerifiedArtifactHashes::currentHash);
    }

    static IncrementalCompileState.ClasspathEntry classpathEntry(
            Path path,
            Function<Path, Optional<String>> verifiedArtifactHash) {
        Path normalized = path.toAbsolutePath().normalize();
        String contentHash = verifiedArtifactHash
                .apply(normalized)
                .orElseGet(() -> hash(normalized));
        if (!Files.isRegularFile(normalized)) {
            return new IncrementalCompileState.ClasspathEntry(normalized, -1L, -1L, contentHash);
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(normalized, BasicFileAttributes.class);
            return new IncrementalCompileState.ClasspathEntry(
                    normalized,
                    attributes.size(),
                    attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                    contentHash);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not read incremental compile input metadata for "
                            + normalized
                            + ". Check that it is readable.",
                    exception);
        }
    }

    static IncrementalCompileState.ClasspathEntry classpathEntry(
            Path path,
            IncrementalCompileState.ClasspathEntry cached) {
        Path normalized = path.toAbsolutePath().normalize();
        return cached != null
                        && cached.path().equals(normalized)
                        && classpathEntryCurrent(cached)
                ? cached
                : classpathEntry(normalized);
    }

    static boolean classpathEntryCurrent(IncrementalCompileState.ClasspathEntry entry) {
        if (!entry.hasRegularFileMetadata()) {
            return entry.hash().equals(hash(entry.path()));
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(entry.path(), BasicFileAttributes.class);
            return attributes.isRegularFile()
                    && attributes.size() == entry.size()
                    && attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS) == entry.lastModifiedNanos();
        } catch (IOException exception) {
            return false;
        }
    }

    static String relative(Path projectRoot, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(projectRoot)) {
            return projectRoot.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString().replace('\\', '/');
    }

    private static String directoryHash(Path directory) {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            StringBuilder content = new StringBuilder();
            paths.filter(Files::isRegularFile)
                    .filter(path -> !LOCAL_COMPILE_METADATA.contains(path.getFileName().toString()))
                    .sorted()
                    .forEach(path -> content
                            .append(directory.relativize(path).toString().replace('\\', '/'))
                            .append('|')
                            .append(hash(path))
                            .append('\n'));
            return sha256(content.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not hash incremental compile directory "
                            + directory
                            + ". Check that it is readable.",
                    exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new BuildException("Could not compute incremental compile plan because SHA-256 is unavailable.", exception);
        }
    }
}
