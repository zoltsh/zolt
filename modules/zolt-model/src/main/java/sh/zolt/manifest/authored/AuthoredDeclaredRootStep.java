package sh.zolt.manifest.authored;

import java.util.List;
import java.util.Objects;
import sh.zolt.manifest.GeneratedStepSettings;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ResourceGlob;

/** Authored {@code kind = "declared-root"} generated step. */
public record AuthoredDeclaredRootStep(
        GeneratedStepSettings settings,
        List<ResourceGlob> inputs,
        ManifestRelativePath output) implements AuthoredGeneratedStep {
    public AuthoredDeclaredRootStep {
        Objects.requireNonNull(settings, "Declared-root step settings must not be null.");
        inputs = ManifestModelValues.orderedDistinctList(inputs, "Declared-root step inputs");
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("A declared-root step requires at least one input.");
        }
        Objects.requireNonNull(output, "Declared-root step output must not be null.");
    }
}
