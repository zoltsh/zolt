package sh.zolt.cache;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.lockfile.CacheRelativePath;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.MavenRepositoryPathBuilder;

/** Read-only access to POMs and their repository-scoped cache provenance. */
public final class ScopedPomCacheReader {
    private final MavenRepositoryPathBuilder pathBuilder = new MavenRepositoryPathBuilder();
    private final ScopedArtifactCacheStorage storage;

    public ScopedPomCacheReader(Path cacheRoot) {
        storage = new ScopedArtifactCacheStorage(Objects.requireNonNull(cacheRoot, "cacheRoot"));
    }

    /** Finds a valid POM in one exact repository scope without fetching or repairing the cache. */
    public Optional<CachedArtifact> find(RepositoryCacheScope scope, Coordinate coordinate) {
        return storage.find(scope, coordinate, pathBuilder.pomPath(coordinate));
    }

    /**
     * Finds repository scopes whose index attributes {@code cachedPath} to {@code source} for the
     * given POM. Consumers can follow metadata within the provenance recorded by a locked blob.
     */
    public List<RepositoryCacheScope> matchingScopes(
            Coordinate coordinate,
            CacheRelativePath cachedPath,
            String source) {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(cachedPath, "cachedPath");
        Objects.requireNonNull(source, "source");
        return storage.matchingScopes(
                coordinate,
                pathBuilder.pomPath(coordinate),
                cachedPath,
                source);
    }
}
