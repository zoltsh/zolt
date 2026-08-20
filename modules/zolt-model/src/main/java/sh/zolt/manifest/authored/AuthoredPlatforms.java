package sh.zolt.manifest.authored;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.PlatformSelector;

/** Immutable authored imported platforms keyed by exact Maven coordinate. */
public record AuthoredPlatforms(Map<DependencyCoordinate, PlatformSelector> entries) {
    public AuthoredPlatforms {
        Objects.requireNonNull(entries, "Authored platforms must not be null.");
        TreeMap<DependencyCoordinate, PlatformSelector> copy = new TreeMap<>();
        entries.forEach((coordinate, selector) -> copy.put(
                Objects.requireNonNull(coordinate, "Platform coordinate must not be null."),
                Objects.requireNonNull(selector, "Platform selector must not be null.")));
        entries = Collections.unmodifiableMap(copy);
    }

    public static AuthoredPlatforms empty() {
        return new AuthoredPlatforms(Map.of());
    }
}
