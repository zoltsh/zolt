package sh.zolt.cache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import sh.zolt.maven.Coordinate;

/** Creates a stable, durable cache-local snapshot from a potentially changing overlay file. */
final class LocalArtifactSnapshotter {
    private static final int MAX_ATTEMPTS = 3;
    private static final int BUFFER_SIZE = 64 * 1024;
    private final SnapshotCheckpoint checkpoint;

    LocalArtifactSnapshotter() {
        this((source, attempt) -> { });
    }

    LocalArtifactSnapshotter(SnapshotCheckpoint checkpoint) {
        this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
    }

    StagedLocalArtifact snapshot(Path source, Path stagingDirectory, Coordinate coordinate) {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Path staging = null;
            try {
                BasicFileAttributes before = attributes(source);
                Files.createDirectories(stagingDirectory);
                staging = Files.createTempFile(stagingDirectory, "overlay-", ".tmp");
                StagedLocalArtifact copied = copy(source, staging);
                checkpoint.afterCopy(source, attempt);
                BasicFileAttributes after = attributes(source);
                if (sameSource(before, after, copied.length())) {
                    return copied;
                }
                lastFailure = new IOException("source identity, size, or modification time changed");
            } catch (IOException exception) {
                lastFailure = exception;
            }
            deleteQuietly(staging);
        }
        throw new ArtifactCacheException(
                "Local artifact "
                        + coordinate
                        + " changed while Zolt was snapshotting it. Retry after the local repository writer finishes.",
                lastFailure);
    }

    private static StagedLocalArtifact copy(Path source, Path staging) throws IOException {
        MessageDigest digest = sha256Digest();
        long length = 0;
        try (InputStream input = Files.newInputStream(source);
                FileChannel output = FileChannel.open(
                        staging,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
                length += read;
            }
            output.force(true);
        }
        return new StagedLocalArtifact(
                staging,
                HexFormat.of().formatHex(digest.digest()),
                length);
    }

    private static BasicFileAttributes attributes(Path source) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                source,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("source is not a regular file");
        }
        return attributes;
    }

    private static boolean sameSource(
            BasicFileAttributes before,
            BasicFileAttributes after,
            long copiedLength) {
        return Objects.equals(before.fileKey(), after.fileKey())
                && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && copiedLength == after.size();
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void deleteQuietly(Path staging) {
        if (staging == null) {
            return;
        }
        try {
            Files.deleteIfExists(staging);
        } catch (IOException ignored) {
        }
    }

    @FunctionalInterface
    interface SnapshotCheckpoint {
        void afterCopy(Path source, int attempt) throws IOException;
    }
}
