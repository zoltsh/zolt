package sh.zolt.manifest;

import java.util.List;
import java.util.Objects;
import sh.zolt.unicode.Unicode17Portability;

/** A normalized workspace member path or strict one-directory-segment pattern. */
public record WorkspaceMemberPattern(String value) implements Comparable<WorkspaceMemberPattern> {
    public WorkspaceMemberPattern {
        Objects.requireNonNull(value, "Workspace member pattern must not be null.");
        value = Unicode17Portability.normalizeNfc(value);
        if (value.isEmpty() || value.startsWith("/") || hasWindowsDrivePrefix(value)) {
            throw invalid(value, "use a nonempty relative member pattern");
        }
        if (value.indexOf('\\') >= 0) {
            throw invalid(value, "use `/` separators on every platform");
        }
        if (!value.equals(".")) {
            for (String segment : value.split("/", -1)) {
                validateSegment(value, segment);
            }
        }
        ManifestModelValues.rejectControlCharacters(value, "Workspace member pattern");
    }

    /** Normalized segments used by deterministic directory discovery. */
    public List<String> segments() {
        return List.of(value.split("/", -1));
    }

    public boolean hasWildcard() {
        return value.indexOf('*') >= 0;
    }

    /**
     * Whether this pattern selects {@code path} under the final one-segment wildcard grammar. A
     * {@code *} segment matches exactly one directory name that does not start with {@code .}, and
     * the root member {@code .} is selected only by the literal {@code .} pattern (design §4.4).
     */
    public boolean matches(WorkspaceMemberPath path) {
        Objects.requireNonNull(path, "Matched workspace member path must not be null.");
        return matchesPath(path.value());
    }

    /**
     * The same selection decision against a raw NFC-normalized {@code /}-separated directory path.
     *
     * <p>Candidate expansion applies exclusions before any candidate earns a strict
     * {@link WorkspaceMemberPath} identity, so both expanders share exactly this matcher rather than
     * requiring an identity that a not-yet-a-member directory may not be able to carry (design §6.5).
     */
    public boolean matchesPath(String normalizedPath) {
        Objects.requireNonNull(normalizedPath, "Matched workspace directory path must not be null.");
        if (value.equals(".") || normalizedPath.equals(".")) {
            return value.equals(normalizedPath);
        }
        List<String> patternSegments = segments();
        List<String> pathSegments = List.of(normalizedPath.split("/", -1));
        if (patternSegments.size() != pathSegments.size()) {
            return false;
        }
        for (int index = 0; index < patternSegments.size(); index++) {
            String expected = patternSegments.get(index);
            String actual = pathSegments.get(index);
            if (expected.equals("*")) {
                if (actual.startsWith(".")) {
                    return false;
                }
            } else if (!expected.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    String portabilityKey() {
        return Unicode17Portability.key(value);
    }

    @Override
    public int compareTo(WorkspaceMemberPattern other) {
        Objects.requireNonNull(other, "Compared workspace member pattern must not be null.");
        return ManifestModelValues.CODE_POINT_ORDER.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }

    private static void validateSegment(String value, String segment) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
            throw invalid(value, "omit empty, `.` and `..` segments");
        }
        if (segment.equals("*")) {
            return;
        }
        if (segment.indexOf('*') >= 0 || segment.indexOf('?') >= 0) {
            throw invalid(value, "use only literal segments or a complete `*` segment");
        }
    }

    private static boolean hasWindowsDrivePrefix(String value) {
        return value.length() >= 2
                && ((value.charAt(0) >= 'A' && value.charAt(0) <= 'Z')
                        || (value.charAt(0) >= 'a' && value.charAt(0) <= 'z'))
                && value.charAt(1) == ':';
    }

    private static IllegalArgumentException invalid(String value, String guidance) {
        return new IllegalArgumentException(
                "Invalid workspace member pattern `" + value + "`: " + guidance + ".");
    }
}
