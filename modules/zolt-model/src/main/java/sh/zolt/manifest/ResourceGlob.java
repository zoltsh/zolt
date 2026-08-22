package sh.zolt.manifest;

import java.util.Objects;
import sh.zolt.unicode.Unicode17Portability;

/** A normalized resource-root-relative glob using Zolt's platform-independent grammar. */
public record ResourceGlob(String value) implements Comparable<ResourceGlob> {
    public ResourceGlob {
        Objects.requireNonNull(value, "Resource glob must not be null.");
        value = Unicode17Portability.normalizeNfc(value);
        if (value.isEmpty() || value.startsWith("/") || hasWindowsDrivePrefix(value)) {
            throw invalid(value, "use a nonempty root-relative glob");
        }
        if (value.indexOf('\\') >= 0) {
            throw invalid(value, "use `/` separators on every platform");
        }
        if (value.indexOf('[') >= 0 || value.indexOf(']') >= 0
                || value.indexOf('{') >= 0 || value.indexOf('}') >= 0) {
            throw invalid(value, "character classes and braces are unsupported");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw invalid(value, "omit empty, `.` and `..` segments");
            }
            if (segment.isBlank()) {
                throw invalid(value, "omit blank segments");
            }
            if (segment.contains("**") && !segment.equals("**")) {
                throw invalid(value, "use `**` only as a complete path segment");
            }
        }
        ManifestModelValues.rejectControlCharacters(value, "Resource glob");
    }

    @Override
    public int compareTo(ResourceGlob other) {
        Objects.requireNonNull(other, "Compared resource glob must not be null.");
        return ManifestModelValues.CODE_POINT_ORDER.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }

    private static boolean hasWindowsDrivePrefix(String value) {
        return value.length() >= 2
                && ((value.charAt(0) >= 'A' && value.charAt(0) <= 'Z')
                        || (value.charAt(0) >= 'a' && value.charAt(0) <= 'z'))
                && value.charAt(1) == ':';
    }

    private static IllegalArgumentException invalid(String value, String guidance) {
        return new IllegalArgumentException(
                "Invalid resource glob `" + value + "`: " + guidance + ".");
    }
}
