package sh.zolt.maven.repository;

import sh.zolt.maven.Coordinate;
import java.net.URI;

public record RepositoryArtifact(
        Coordinate coordinate,
        String path,
        URI source,
        String repositoryId,
        byte[] bytes) {
    public RepositoryArtifact {
        repositoryId = repositoryId == null ? "" : repositoryId;
        bytes = bytes.clone();
    }

    public RepositoryArtifact(Coordinate coordinate, String path, URI source, byte[] bytes) {
        this(coordinate, path, source, "", bytes);
    }

    public RepositoryArtifact withRepositoryId(String selectedRepositoryId) {
        return new RepositoryArtifact(coordinate, path, source, selectedRepositoryId, bytes);
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
