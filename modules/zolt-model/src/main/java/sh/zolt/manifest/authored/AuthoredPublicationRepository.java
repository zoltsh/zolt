package sh.zolt.manifest.authored;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.RepositoryUrl;

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
