package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;

final class AuthoredGeneratedPresetsTest {
    @Test
    void preservesEveryOpenApiFieldAndThirdPartyKeySpelling() {
        LinkedHashMap<String, String> configOptions = new LinkedHashMap<>();
        configOptions.put("useJakartaEe", "true");
        configOptions.put("dateLibrary", "java8");
        AuthoredOpenApiOptions options = new AuthoredOpenApiOptions(
                Optional.of("java"),
                Optional.of("webclient"),
                Optional.of("com.example.api"),
                Optional.of("com.example.model"),
                Optional.of("com.example.invoker"),
                Optional.of(new ManifestRelativePath("openapi/config.json")),
                Optional.of(new ManifestRelativePath("openapi/templates")),
                Optional.of(true),
                Map.of("skipFormModel", "false"),
                Map.of("hideGenerationTimestamp", "true"),
                configOptions,
                Map.of("models", ""),
                Map.of("OffsetDateTime", "Instant"),
                Map.of("Instant", "java.time.Instant"));
        configOptions.clear();

        assertEquals("useJakartaEe", options.configOptions().keySet().stream()
                .filter(key -> key.startsWith("use"))
                .findFirst()
                .orElseThrow());
        assertEquals("", options.globalProperties().get("models"));
        assertThrows(UnsupportedOperationException.class, () -> options.configOptions().clear());
    }

    @Test
    void presetsAreSortedAndDefensivelyCopied() {
        LinkedHashMap<LocalId, AuthoredOpenApiOptions> source = new LinkedHashMap<>();
        source.put(new LocalId("z-client"), AuthoredOpenApiOptions.empty());
        source.put(new LocalId("a-client"), AuthoredOpenApiOptions.empty());

        AuthoredGeneratedPresets presets = new AuthoredGeneratedPresets(source);
        source.clear();

        assertEquals(
                List.of("a-client", "z-client"),
                presets.openApi().keySet().stream().map(LocalId::value).toList());
        assertThrows(UnsupportedOperationException.class, () -> presets.openApi().clear());
    }

    @Test
    void textualOptionsRejectBlankOrControlContentAtTheOwnedBoundary() {
        assertThrows(
                IllegalArgumentException.class,
                () -> optionsWithGenerator(" "));
        assertThrows(
                IllegalArgumentException.class,
                () -> optionsWithGenerator("java\n"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredOpenApiOptions(
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Map.of("bad\nkey", "value"), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
    }

    @Test
    void generatorPostProcessingHooksAreRejectedInEveryOptionMap() {
        // The generator shells out to $JAVA_POST_PROCESS_FILE once per generated file when any of
        // these are set, escaping supervision and generated-source fingerprinting. All four option
        // maps reach --additional-properties/--global-property, so all four reject the keys.
        for (String map : List.of("options", "additionalProperties", "configOptions", "globalProperties")) {
            for (String key : List.of(
                    "enablePostProcessFile",
                    "ENABLEPOSTPROCESSFILE",
                    "enablepostprocessfile",
                    "apiFilePostProcessFile",
                    "modelFilePostProcessFile",
                    "postProcessFile",
                    "somethingPostProcessLike")) {
                IllegalArgumentException failure = assertThrows(
                        IllegalArgumentException.class,
                        () -> optionsWithMap(map, key, "/usr/bin/env"),
                        key + " in " + map);
                assertTrue(failure.getMessage().contains(key), failure.getMessage());
                assertTrue(
                        failure.getMessage().contains("does not run generator post-processing hooks"),
                        failure.getMessage());
            }
        }
    }

    @Test
    void typeAndImportMappingsStillAcceptKeysThatMerelyLookLikeHooks() {
        // typeMappings/importMappings never reach a generator property, so they keep the open spelling.
        AuthoredOpenApiOptions options = optionsWithMap("typeMappings", "PostProcessFile", "String");

        assertEquals("String", options.typeMappings().get("PostProcessFile"));
    }

    private static AuthoredOpenApiOptions optionsWithMap(String map, String key, String value) {
        Map<String, String> entry = Map.of(key, value);
        Map<String, String> none = Map.of();
        return new AuthoredOpenApiOptions(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                "options".equals(map) ? entry : none,
                "additionalProperties".equals(map) ? entry : none,
                "configOptions".equals(map) ? entry : none,
                "globalProperties".equals(map) ? entry : none,
                "typeMappings".equals(map) ? entry : none,
                "importMappings".equals(map) ? entry : none);
    }

    private static AuthoredOpenApiOptions optionsWithGenerator(String generator) {
        return new AuthoredOpenApiOptions(
                Optional.of(generator), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }
}
