package sh.zolt.project;

import java.util.Optional;

/** One resolved direct processor root from a {@code [generated.tools.<id>]} KSP declaration. */
public record KspProcessorSettings(
        String coordinate,
        String version,
        Optional<String> versionRef) {
    public KspProcessorSettings {
        coordinate = requireNonBlank(coordinate, "KSP processor coordinate");
        version = requireNonBlank(version, "KSP processor version");
        versionRef = versionRef == null
                ? Optional.empty()
                : versionRef.filter(value -> !value.isBlank()).map(String::strip);
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return value.strip();
    }
}
