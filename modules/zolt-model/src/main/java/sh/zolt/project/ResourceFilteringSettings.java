package sh.zolt.project;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record ResourceFilteringSettings(
        boolean enabled,
        boolean testEnabled,
        List<String> includes,
        ResourceMissingTokenPolicy missing,
        Map<String, ResourceTokenSettings> tokens) {
    public ResourceFilteringSettings {
        includes = includes == null ? List.of() : List.copyOf(includes);
        missing = missing == null ? ResourceMissingTokenPolicy.FAIL : missing;
        tokens = tokens == null
                ? Map.of()
                : Collections.unmodifiableSortedMap(new TreeMap<>(tokens));
    }

    public static ResourceFilteringSettings defaults() {
        return new ResourceFilteringSettings(false, false, List.of(), ResourceMissingTokenPolicy.FAIL, Map.of());
    }
}
