package sh.zolt.manifest.authored;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.GeneratedStepSettings;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ResourceGlob;

/** Authored {@code kind = "openapi"} generated step. */
public record AuthoredOpenApiStep(
        GeneratedStepSettings settings,
        Optional<LocalId> tool,
        ResourceGlob input,
        Optional<ManifestRelativePath> output,
        Optional<LocalId> preset,
        AuthoredOpenApiOptions overrides) implements AuthoredGeneratedStep {
    public AuthoredOpenApiStep {
        Objects.requireNonNull(settings, "OpenAPI step settings must not be null.");
        tool = Objects.requireNonNull(tool, "OpenAPI step tool reference must not be null.");
        Objects.requireNonNull(input, "OpenAPI step input must not be null.");
        output = Objects.requireNonNull(output, "OpenAPI step output must not be null.");
        preset = Objects.requireNonNull(preset, "OpenAPI step preset reference must not be null.");
        Objects.requireNonNull(overrides, "OpenAPI step overrides must not be null.");
    }
}
