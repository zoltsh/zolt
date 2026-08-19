package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/** Authored release and snapshot repository selections from {@code [publish]}. */
public record AuthoredPublicationRoutes(
        Optional<LocalId> release,
        Optional<LocalId> snapshot) {
    public AuthoredPublicationRoutes {
        release = Objects.requireNonNull(release, "Release publication route must not be null.");
        snapshot = Objects.requireNonNull(snapshot, "Snapshot publication route must not be null.");
        if (release.isEmpty() && snapshot.isEmpty()) {
            throw new IllegalArgumentException("Authored publication routes must not be empty.");
        }
    }
}
