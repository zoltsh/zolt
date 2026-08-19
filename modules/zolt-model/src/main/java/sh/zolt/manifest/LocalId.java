package sh.zolt.manifest;

import java.util.Objects;
import java.util.regex.Pattern;

/** A Zolt-owned local identifier using lowercase kebab-case. */
public record LocalId(String value) implements Comparable<LocalId> {
    private static final Pattern GRAMMAR = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    public LocalId {
        Objects.requireNonNull(value, "Local ID must not be null.");
        if (!GRAMMAR.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid local ID `" + value + "`: use lowercase kebab-case.");
        }
    }

    @Override
    public int compareTo(LocalId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
