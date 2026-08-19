package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/** One named dependency repository definition, excluding its map-owned ID. */
public record DependencyRepository(RepositoryUrl url, Optional<LocalId> credentials) {
    public DependencyRepository {
        Objects.requireNonNull(url, "Dependency repository URL must not be null.");
        credentials = Objects.requireNonNull(
                credentials, "Dependency repository credential reference must not be null.");
    }

    public static DependencyRepository unauthenticated(RepositoryUrl url) {
        return new DependencyRepository(url, Optional.empty());
    }
}
