package sh.zolt.manifest.authored;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.GeneratedStepSettings;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.ManifestRelativePath;

/** Authored {@code kind = "ksp"} generated step. */
public record AuthoredKspStep(
        GeneratedStepSettings settings,
        Optional<LocalId> tool,
        Optional<ManifestRelativePath> output,
        Map<String, String> options) implements AuthoredGeneratedStep {
    public AuthoredKspStep {
        Objects.requireNonNull(settings, "KSP step settings must not be null.");
        if (settings.language().isPresent()) {
            throw new IllegalArgumentException(
                    "A KSP step must not declare a language; its owned output has fixed Kotlin, Java, and resource lanes.");
        }
        if (settings.required().filter(value -> !value).isPresent()) {
            throw new IllegalArgumentException("A KSP step must be required.");
        }
        if (settings.clean().filter(value -> !value).isPresent()) {
            throw new IllegalArgumentException(
                    "A KSP step must clean its owned output before each non-incremental run.");
        }
        tool = Objects.requireNonNull(tool, "KSP step tool reference must not be null.");
        output = Objects.requireNonNull(output, "KSP step output must not be null.");
        options = ManifestModelValues.immutableSortedMap(
                options,
                ManifestModelValues.CODE_POINT_ORDER,
                "KSP processor option name",
                "KSP processor option value");
        options.forEach((key, value) ->
                ManifestModelValues.requireNonBlank(key, "KSP processor option name"));
    }
}
