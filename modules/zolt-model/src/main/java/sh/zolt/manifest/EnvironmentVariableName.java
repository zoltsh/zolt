package sh.zolt.manifest;

import java.util.Objects;
import java.util.regex.Pattern;

/** A case-preserving portable environment-variable name. */
public record EnvironmentVariableName(String value) implements Comparable<EnvironmentVariableName> {
    private static final Pattern GRAMMAR = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public EnvironmentVariableName {
        Objects.requireNonNull(value, "Environment-variable name must not be null.");
        if (!GRAMMAR.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid environment-variable name `" + value + "`: use `[A-Za-z_][A-Za-z0-9_]*`.");
        }
    }

    @Override
    public int compareTo(EnvironmentVariableName other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
