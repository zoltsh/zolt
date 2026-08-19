package sh.zolt.manifest;

import java.util.Objects;

/** A bare executable name resolved only from Zolt's curated process path. */
public record GeneratedProcessBinary(String value) {
    private static final String SHELL_METACHARACTERS = ";&|$%><^`'\"(){}[]*?~!#";

    public GeneratedProcessBinary {
        Objects.requireNonNull(value, "Generated process binary must not be null.");
        ManifestModelValues.requireNonBlank(value, "Generated process binary");
        ManifestModelValues.rejectControlCharacters(value, "Generated process binary");
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || hasWindowsDrivePrefix(value)) {
            throw invalid(value, "use a bare executable name without path separators");
        }
        for (int codePoint : value.codePoints().toArray()) {
            if (Character.isWhitespace(codePoint)
                    || SHELL_METACHARACTERS.indexOf(codePoint) >= 0) {
                throw invalid(value, "whitespace and shell syntax are forbidden");
            }
        }
    }

    @Override
    public String toString() {
        return value;
    }

    private static IllegalArgumentException invalid(String value, String guidance) {
        return new IllegalArgumentException(
                "Invalid generated process binary `" + value + "`: " + guidance + ".");
    }

    private static boolean hasWindowsDrivePrefix(String value) {
        return value.length() >= 2
                && ((value.charAt(0) >= 'A' && value.charAt(0) <= 'Z')
                        || (value.charAt(0) >= 'a' && value.charAt(0) <= 'z'))
                && value.charAt(1) == ':';
    }
}
