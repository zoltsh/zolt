package sh.zolt.manifest;

import java.util.Objects;
import sh.zolt.unicode.Unicode17Portability;

/** A normalized authored path contained beneath the project or workspace that owns it. */
public record ManifestRelativePath(String value) implements Comparable<ManifestRelativePath> {
    public ManifestRelativePath {
        Objects.requireNonNull(value, "Manifest path must not be null.");
        value = Unicode17Portability.normalizeNfc(value);
        if (value.isEmpty() || value.startsWith("/") || hasWindowsDrivePrefix(value)) {
            throw invalid(value, "use a nonempty relative path");
        }
        if (value.indexOf('\\') >= 0) {
            throw invalid(value, "use `/` separators on every platform");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw invalid(value, "omit empty, `.` and `..` segments");
            }
            if (segment.isBlank()) {
                throw invalid(value, "omit blank segments");
            }
        }
        for (int codePoint : value.codePoints().toArray()) {
            if (codePoint == 0 || Character.isISOControl(codePoint)) {
                throw invalid(value, "omit NUL and control characters");
            }
        }
    }

    @Override
    public int compareTo(ManifestRelativePath other) {
        Objects.requireNonNull(other, "Compared manifest path must not be null.");
        int[] left = value.codePoints().toArray();
        int[] right = other.value.codePoints().toArray();
        int shared = Math.min(left.length, right.length);
        for (int index = 0; index < shared; index++) {
            int comparison = Integer.compare(left[index], right[index]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
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
                "Invalid manifest path `" + value + "`: " + guidance + ".");
    }
}
