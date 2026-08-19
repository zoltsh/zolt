package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/** One named project-local publication repository, excluding its map-owned ID. */
public record AuthoredPublicationRepository(
        RepositoryUrl url,
        Optional<LocalId> credentials) {
    public AuthoredPublicationRepository {
        Objects.requireNonNull(url, "Publication repository URL must not be null.");
        credentials = Objects.requireNonNull(
                credentials, "Publication repository credential reference must not be null.");
    }

    public static AuthoredPublicationRepository unauthenticated(RepositoryUrl url) {
        return new AuthoredPublicationRepository(url, Optional.empty());
    }
}
