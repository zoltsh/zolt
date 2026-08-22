package sh.zolt.manifest;

import java.util.Objects;
import java.util.regex.Pattern;

/** An exact manifest coordinate containing only a Maven group and artifact identity. */
public record DependencyCoordinate(String value) implements Comparable<DependencyCoordinate> {
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");

    public DependencyCoordinate {
        Objects.requireNonNull(value, "Dependency coordinate must not be null.");
        int separator = value.indexOf(':');
        if (separator <= 0
                || separator != value.lastIndexOf(':')
                || separator == value.length() - 1
                || !SEGMENT.matcher(value.substring(0, separator)).matches()
                || !SEGMENT.matcher(value.substring(separator + 1)).matches()) {
            throw new IllegalArgumentException(
                    "Invalid dependency coordinate `" + value + "`: use exact `group:artifact` syntax with "
                            + "ASCII letters, digits, `_`, `.`, and `-` in each segment.");
        }
    }

    public String group() {
        return value.substring(0, value.indexOf(':'));
    }

    public String artifact() {
        return value.substring(value.indexOf(':') + 1);
    }

    @Override
    public int compareTo(DependencyCoordinate other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
