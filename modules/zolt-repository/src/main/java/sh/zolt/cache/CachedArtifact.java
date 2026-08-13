package sh.zolt.cache;

import sh.zolt.maven.Coordinate;
import java.nio.file.Path;

public record CachedArtifact(
        Coordinate coordinate,
        String repositoryPath,
        Path cachePath,
        byte[] bytes,
        String source) {
    public CachedArtifact {
        bytes = bytes.clone();
        source = source == null ? "" : source;
    }

    public CachedArtifact(Coordinate coordinate, String repositoryPath, Path cachePath, byte[] bytes) {
        this(coordinate, repositoryPath, cachePath, bytes, "");
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
