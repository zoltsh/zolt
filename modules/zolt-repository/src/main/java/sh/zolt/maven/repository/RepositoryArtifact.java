package sh.zolt.maven.repository;

import sh.zolt.maven.Coordinate;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** A repository response backed by a temporary file rather than an in-heap artifact body. */
public record RepositoryArtifact(
        Coordinate coordinate,
        String path,
        URI source,
        String repositoryId,
        Path temporaryPath,
        long size,
        String sha256) implements AutoCloseable {
    public RepositoryArtifact {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(temporaryPath, "temporaryPath");
        repositoryId = repositoryId == null ? "" : repositoryId;
        if (size < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Repository artifact requires a size and SHA-256 digest.");
        }
    }

    public RepositoryArtifact(Coordinate coordinate, String path, URI source, byte[] bytes) {
        this(coordinate, path, source, "", temporary(bytes), bytes.length, sha256(bytes));
    }

    public RepositoryArtifact(
            Coordinate coordinate,
            String path,
            URI source,
            String repositoryId,
            byte[] bytes) {
        this(coordinate, path, source, repositoryId, temporary(bytes), bytes.length, sha256(bytes));
    }

    public RepositoryArtifact withRepositoryId(String selectedRepositoryId) {
        return new RepositoryArtifact(
                coordinate,
                path,
                source,
                selectedRepositoryId,
                temporaryPath,
                size,
                sha256);
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(temporaryPath);
        } catch (IOException ignored) {
            // Best effort for an unconsumed response; successful cache stores move this file.
        }
    }

    private static Path temporary(byte[] bytes) {
        try {
            Path path = Files.createTempFile("zolt-repository-artifact-", ".tmp");
            Files.write(path, bytes);
            return path;
        } catch (IOException exception) {
            throw new RepositoryClientException("Could not create temporary repository artifact.", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
