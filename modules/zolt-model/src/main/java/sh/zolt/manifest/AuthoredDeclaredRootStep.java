package sh.zolt.manifest;

import java.util.List;
import java.util.Objects;

/** Authored {@code kind = "declared-root"} generated step. */
public record AuthoredDeclaredRootStep(
        GeneratedStepSettings settings,
        List<ResourceGlob> inputs,
        ManifestRelativePath output) implements AuthoredGeneratedStep {
    public AuthoredDeclaredRootStep {
        Objects.requireNonNull(settings, "Declared-root step settings must not be null.");
        inputs = ManifestModelValues.sortedDistinctList(inputs, "Declared-root step inputs");
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("A declared-root step requires at least one input.");
        }
        Objects.requireNonNull(output, "Declared-root step output must not be null.");
    }
}
