package sh.zolt.manifest;

import java.util.Objects;
import sh.zolt.unicode.Unicode17Portability;

/** One normalized exact workspace member path, including the root-project special case {@code .}. */
public record WorkspaceMemberPath(String value) implements Comparable<WorkspaceMemberPath> {
    public WorkspaceMemberPath {
        Objects.requireNonNull(value, "Workspace member path must not be null.");
        value = Unicode17Portability.normalizeNfc(value);
        if (value.isEmpty() || value.startsWith("/") || hasWindowsDrivePrefix(value)) {
            throw invalid(value, "use a nonempty relative member path");
        }
        if (value.indexOf('\\') >= 0) {
            throw invalid(value, "use `/` separators on every platform");
        }
        if (!value.equals(".")) {
            for (String segment : value.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    throw invalid(value, "omit empty, `.` and `..` segments");
                }
                if (containsPatternSyntax(segment)) {
                    throw invalid(value, "use an exact member path without pattern syntax");
                }
            }
        }
        ManifestModelValues.rejectControlCharacters(value, "Workspace member path");
    }

    /** Cross-package model support for pinned Unicode portability comparisons. */
    public String portabilityKey() {
        return Unicode17Portability.key(value);
    }

    @Override
    public int compareTo(WorkspaceMemberPath other) {
        Objects.requireNonNull(other, "Compared workspace member path must not be null.");
        return ManifestModelValues.CODE_POINT_ORDER.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }

    private static boolean containsPatternSyntax(String segment) {
        return segment.indexOf('*') >= 0
                || segment.indexOf('?') >= 0
                || segment.indexOf('[') >= 0
                || segment.indexOf(']') >= 0
                || segment.indexOf('{') >= 0
                || segment.indexOf('}') >= 0;
    }

    private static boolean hasWindowsDrivePrefix(String value) {
        return value.length() >= 2
                && ((value.charAt(0) >= 'A' && value.charAt(0) <= 'Z')
                        || (value.charAt(0) >= 'a' && value.charAt(0) <= 'z'))
                && value.charAt(1) == ':';
    }

    private static IllegalArgumentException invalid(String value, String guidance) {
        return new IllegalArgumentException(
                "Invalid workspace member path `" + value + "`: " + guidance + ".");
    }
}
