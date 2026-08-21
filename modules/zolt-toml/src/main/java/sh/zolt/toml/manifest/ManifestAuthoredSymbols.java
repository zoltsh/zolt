package sh.zolt.toml.manifest;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Maps shape-validated manifest symbols to parser-independent authored values. */
final class ManifestAuthoredSymbols {
    private ManifestAuthoredSymbols() {
    }

    static <T> T authored(
            ValidatedManifestField field,
            String value,
            T[] candidates,
            Function<T, String> configValue) {
        return required(
                value, candidates, configValue, () -> unrecognizedAuthored(field, value));
    }

    static <T> T authored(
            ValidatedManifestField field,
            String value,
            Function<String, Optional<T>> modelLookup) {
        return modelLookup.apply(value).orElseThrow(() -> unrecognizedAuthored(field, value));
    }

    static <T> T model(
            ValidatedManifestField field,
            String value,
            T[] candidates,
            Function<T, String> configValue,
            String subject) {
        return required(value, candidates, configValue, () -> new IllegalStateException(
                "Final manifest schema accepted " + subject + " `" + value + "` at `"
                        + field.path() + "` but the model does not recognize it."));
    }

    private static <T> T required(
            String value,
            T[] candidates,
            Function<T, String> configValue,
            Supplier<IllegalStateException> failure) {
        for (T candidate : candidates) {
            if (configValue.apply(candidate).equals(value)) {
                return candidate;
            }
        }
        throw failure.get();
    }

    private static IllegalStateException unrecognizedAuthored(
            ValidatedManifestField field,
            String value) {
        return new IllegalStateException(
                "Final manifest schema accepted symbol `" + value + "` for `"
                        + field.path() + "` but the authored model does not recognize it.");
    }
}
