package sh.zolt.manifest;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import sh.zolt.dependency.DependencyLane;

/** One validated direct dependency declaration with its authored lane retained. */
public record AuthoredDependency(
        DependencyLane lane,
        DependencyCoordinate coordinate,
        DependencySelector selector,
        AuthoredDependencyMetadata metadata) {
    private static final Set<DependencyLane> OPTIONAL_LANES = EnumSet.of(
            DependencyLane.API,
            DependencyLane.IMPLEMENTATION,
            DependencyLane.RUNTIME);
    private static final Set<DependencyLane> PUBLISH_ONLY_LANES = EnumSet.of(
            DependencyLane.API,
            DependencyLane.IMPLEMENTATION,
            DependencyLane.RUNTIME,
            DependencyLane.PROVIDED);

    public AuthoredDependency {
        Objects.requireNonNull(lane, "Dependency lane must not be null.");
        Objects.requireNonNull(coordinate, "Dependency coordinate must not be null.");
        Objects.requireNonNull(selector, "Dependency selector must not be null.");
        Objects.requireNonNull(metadata, "Dependency metadata must not be null.");

        if (metadata.optional() && !OPTIONAL_LANES.contains(lane)) {
            throw new IllegalArgumentException(
                    "Optional dependency metadata is not meaningful in the " + lane + " lane.");
        }
        if (metadata.publishOnly() && !PUBLISH_ONLY_LANES.contains(lane)) {
            throw new IllegalArgumentException(
                    "Publish-only dependency metadata is not allowed in the " + lane + " lane.");
        }
        if (metadata.publishOnly()
                && !(selector instanceof DependencySelector.FixedVersion
                        || selector instanceof DependencySelector.VersionReference)) {
            throw new IllegalArgumentException(
                    "Publish-only dependencies require a fixed version or version reference.");
        }
        if (selector instanceof DependencySelector.Workspace) {
            if (metadata.publishOnly()) {
                throw new IllegalArgumentException("Workspace dependencies cannot be publish-only.");
            }
            if (metadata.hasExternalArtifactMetadata()) {
                throw new IllegalArgumentException(
                        "Workspace dependencies cannot declare classifier, type, or exclusion metadata.");
            }
        }
    }

    public DependencyVariant variant() {
        return DependencyVariant.of(this);
    }
}
