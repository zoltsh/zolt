package sh.zolt.cache;

import sh.zolt.maven.Coordinate;
import java.nio.file.Path;
import java.util.Objects;

/** Immutable metadata for an artifact whose bytes live only at {@link #cachePath()}. */
public record CachedArtifact(
        Coordinate coordinate,
        String repositoryPath,
        Path cachePath,
        long size,
        String sha256,
        String source) {
    public CachedArtifact {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(repositoryPath, "repositoryPath");
        Objects.requireNonNull(cachePath, "cachePath");
        source = source == null ? "" : source;
        if (size < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Cached artifact requires a size and SHA-256 digest.");
        }
    }
}
