package sh.zolt.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ResourceFilteringSettingsTest {
    @Test
    void normalizesResourceTokensByName() {
        Map<String, ResourceTokenSettings> tokens = new LinkedHashMap<>();
        tokens.put("projectVersion", ResourceTokenSettings.project("version"));
        tokens.put("enterprisePlatformVersion", ResourceTokenSettings.literal("2026.06"));

        ResourceFilteringSettings settings = new ResourceFilteringSettings(
                true,
                false,
                List.of("**/*.properties"),
                ResourceMissingTokenPolicy.FAIL,
                tokens);

        assertEquals(
                List.of("enterprisePlatformVersion", "projectVersion"),
                List.copyOf(settings.tokens().keySet()));
    }
}
