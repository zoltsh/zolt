package sh.zolt.manifest;

import java.util.Comparator;
import java.util.Map;

/** Immutable authored OpenAPI presets keyed by local ID. */
public record AuthoredGeneratedPresets(Map<LocalId, AuthoredOpenApiOptions> openApi) {
    public AuthoredGeneratedPresets {
        openApi = ManifestModelValues.immutableSortedMap(
                openApi,
                Comparator.naturalOrder(),
                "Generated preset ID",
                "Generated OpenAPI preset");
    }

    public static AuthoredGeneratedPresets empty() {
        return new AuthoredGeneratedPresets(Map.of());
    }
}
