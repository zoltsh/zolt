package sh.zolt.explain.emit;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JavaVersionNotation {
    private static final Pattern FEATURE = Pattern.compile("\\d+");
    private static final Pattern LEGACY_FEATURE = Pattern.compile("1\\.(\\d+)");

    private JavaVersionNotation() {
    }

    static String normalizeLegacyFeature(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        Matcher matcher = LEGACY_FEATURE.matcher(stripped);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return value;
    }

    /** The Java feature release the final language authors as an integer, when one is readable. */
    static Optional<Integer> featureRelease(String value) {
        return liveFeature(value).map(Integer::parseInt);
    }

    static Optional<String> liveFeature(String value) {
        String normalized = normalizeLegacyFeature(value);
        if (normalized == null) {
            return Optional.empty();
        }
        String stripped = normalized.strip();
        if (FEATURE.matcher(stripped).matches()) {
            return Optional.of(stripped);
        }
        return Optional.empty();
    }

    static String reviewValue(String value) {
        String normalized = normalizeLegacyFeature(value);
        if (normalized == null || normalized.isBlank()) {
            return "unknown";
        }
        return normalized.strip();
    }
}
