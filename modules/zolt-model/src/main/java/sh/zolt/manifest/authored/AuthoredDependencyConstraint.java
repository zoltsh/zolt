package sh.zolt.manifest.authored;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.ManifestModelValues;

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
