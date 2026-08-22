package sh.zolt.toml.schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable canonical-order lookup for every closed manifest symbol family. */
public final class ManifestSymbolRegistry {
    private final List<ManifestSymbolFamily> families;
    private final Map<String, ManifestSymbolFamily> familiesByName;

    public ManifestSymbolRegistry(Collection<ManifestSymbolFamily> families) {
        Objects.requireNonNull(families, "Manifest symbol families are required.");
        ArrayList<ManifestSymbolFamily> ordered = new ArrayList<>();
        LinkedHashMap<String, ManifestSymbolFamily> indexed = new LinkedHashMap<>();
        for (ManifestSymbolFamily family : families) {
            ManifestSymbolFamily value = Objects.requireNonNull(
                    family, "Manifest symbol families must not contain null.");
            if (indexed.putIfAbsent(value.name(), value) != null) {
                throw new IllegalArgumentException(
                        "Duplicate manifest symbol family `" + value.name() + "`.");
            }
            ordered.add(value);
        }
        this.families = List.copyOf(ordered);
        this.familiesByName = Map.copyOf(indexed);
    }

    public List<ManifestSymbolFamily> families() {
        return families;
    }

    public Optional<ManifestSymbolFamily> family(String name) {
        return Optional.ofNullable(familiesByName.get(
                Objects.requireNonNull(name, "Manifest symbol family name is required.")));
    }
}
