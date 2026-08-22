package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/** One exact direct-dependency denial from {@code [dependencies.policy]}. */
public record DependencyDenyEntry(
        DependencyCoordinate coordinate,
        Optional<String> reason) {
    public DependencyDenyEntry {
        Objects.requireNonNull(coordinate, "Denied dependency coordinate must not be null.");
        reason = Objects.requireNonNull(reason, "Denied dependency reason must not be null.");
        reason.ifPresent(value -> ManifestModelValues.requireNonBlank(value, "Denied dependency reason"));
    }
}
