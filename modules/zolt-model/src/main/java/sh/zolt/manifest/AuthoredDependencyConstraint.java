package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/** One authored strict constraint value keyed by coordinate in {@code [dependencies.constraints]}. */
public record AuthoredDependencyConstraint(
        DependencyConstraintSelector selector,
        Optional<String> reason) {
    public AuthoredDependencyConstraint {
        Objects.requireNonNull(selector, "Dependency constraint selector must not be null.");
        reason = Objects.requireNonNull(reason, "Dependency constraint reason must not be null.");
        reason.ifPresent(value -> ManifestModelValues.requireNonBlank(value, "Dependency constraint reason"));
    }
}
