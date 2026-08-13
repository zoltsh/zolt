package sh.zolt.cache;

import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.RepositoryArtifact;
import java.nio.file.Path;

/** Fetches directly into a cache-owned staging directory. */
@FunctionalInterface
public interface StreamingArtifactFetcher {
    RepositoryArtifact fetch(Coordinate coordinate, Path downloadDirectory);
}
