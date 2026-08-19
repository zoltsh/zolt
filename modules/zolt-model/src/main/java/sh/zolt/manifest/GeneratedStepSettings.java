package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/** Authored common generated-step fields with behavioral defaults left absent. */
public record GeneratedStepSettings(
        Optional<GeneratedLanguage> language,
        Optional<Boolean> required,
        Optional<Boolean> clean) {
    public GeneratedStepSettings {
        language = Objects.requireNonNull(
                language, "Generated-step language must not be null.");
        required = Objects.requireNonNull(
                required, "Generated-step required setting must not be null.");
        clean = Objects.requireNonNull(
                clean, "Generated-step clean setting must not be null.");
    }

    public static GeneratedStepSettings defaultsOmitted() {
        return new GeneratedStepSettings(
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
