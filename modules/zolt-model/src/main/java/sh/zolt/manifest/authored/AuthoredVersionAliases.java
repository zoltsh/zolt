package sh.zolt.manifest.authored;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.VersionAliasValue;

/** Immutable authored fixed-version aliases keyed by final-language local IDs. */
public record AuthoredVersionAliases(Map<LocalId, VersionAliasValue> entries) {
    public AuthoredVersionAliases {
        Objects.requireNonNull(entries, "Authored version aliases must not be null.");
        TreeMap<LocalId, VersionAliasValue> copy = new TreeMap<>();
        entries.forEach((id, value) -> copy.put(
                Objects.requireNonNull(id, "Version alias ID must not be null."),
                Objects.requireNonNull(value, "Version alias value must not be null.")));
        entries = Collections.unmodifiableMap(copy);
    }

    public static AuthoredVersionAliases empty() {
        return new AuthoredVersionAliases(Map.of());
    }
}
