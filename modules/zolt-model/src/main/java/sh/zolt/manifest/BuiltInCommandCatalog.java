package sh.zolt.manifest;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Exact root-command names supplied by the authoritative CLI/schema composition layer. */
public final class BuiltInCommandCatalog {
    private final Set<LocalId> names;

    private BuiltInCommandCatalog(Collection<LocalId> values) {
        Objects.requireNonNull(values, "Built-in command names must not be null.");
        TreeSet<LocalId> sorted = new TreeSet<>();
        for (LocalId value : values) {
            sorted.add(Objects.requireNonNull(value, "Built-in command name must not be null."));
        }
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("Built-in command names must not be empty.");
        }
        names = Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    public static BuiltInCommandCatalog fromStrings(Collection<String> values) {
        Objects.requireNonNull(values, "Built-in command names must not be null.");
        return new BuiltInCommandCatalog(values.stream().map(LocalId::new).toList());
    }

    public Set<LocalId> names() {
        return names;
    }

    public boolean contains(LocalId name) {
        return names.contains(Objects.requireNonNull(name, "Built-in command name must not be null."));
    }
}
