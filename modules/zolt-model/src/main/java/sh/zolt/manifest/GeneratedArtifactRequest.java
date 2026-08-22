package sh.zolt.manifest;

import java.util.Objects;

/** One immutable artifact request used to run a generated-source tool. */
public record GeneratedArtifactRequest(
        DependencyCoordinate coordinate,
        DependencySelector selector) {
    public GeneratedArtifactRequest {
        Objects.requireNonNull(coordinate, "Generated-tool coordinate must not be null.");
        Objects.requireNonNull(selector, "Generated-tool version selector must not be null.");
        if (!(selector instanceof DependencySelector.FixedVersion)
                && !(selector instanceof DependencySelector.VersionReference)) {
            throw new IllegalArgumentException(
                    "Generated-tool artifacts require a fixed version or version reference.");
        }
    }
}
