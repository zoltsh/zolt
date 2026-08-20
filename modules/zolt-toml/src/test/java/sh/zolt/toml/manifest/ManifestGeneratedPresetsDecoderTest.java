package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredGeneratedPresets;
import sh.zolt.manifest.authored.AuthoredOpenApiOptions;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestGeneratedPresetFields;
import sh.zolt.toml.schema.FinalManifestSchema;

final class ManifestGeneratedPresetsDecoderTest {
    @Test
    void preservesOmissionAndExplicitEmptyCollectionPresence() {
        assertTrue(decode("").isEmpty());

        for (String source : List.of(
                "[generated.presets]\n",
                "generated = { presets = {} }\n")) {
            AuthoredGeneratedPresets presets = decode(source).orElseThrow();
            assertTrue(presets.openApi().isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> presets.openApi().clear());
        }
    }

    @Test
    void decodesEveryFieldAndSortsNamedOnlyPresetsWithoutApplyingDefaults() {
        AuthoredGeneratedPresets presets = decode("""
                [generated.presets.z-client]
                kind = "openapi"
                generator = "java"
                library = "webclient"
                apiPackage = "com.example.api"
                modelPackage = "com.example.model"
                invokerPackage = "com.example.invoker"
                config = "missing/openapi/config.json"
                templateDir = "missing/openapi/templates"
                validateSpec = false
                options = { zKey = "", "Case.Key" = "line one\\nline two" }
                additionalProperties = { hideGenerationTimestamp = "true" }
                configOptions = { useJakartaEe = "true" }
                globalProperties = { models = "" }
                typeMappings = { OffsetDateTime = "Instant" }
                importMappings = { Instant = "java.time.Instant" }

                [generated.presets.a-client]
                kind = "openapi"
                """).orElseThrow();

        assertEquals(
                List.of("a-client", "z-client"),
                presets.openApi().keySet().stream().map(LocalId::value).toList());
        assertEquals(AuthoredOpenApiOptions.empty(), presets.openApi().get(id("a-client")));

        AuthoredOpenApiOptions options = presets.openApi().get(id("z-client"));
        assertEquals("java", options.generator().orElseThrow());
        assertEquals("webclient", options.library().orElseThrow());
        assertEquals("com.example.api", options.apiPackage().orElseThrow());
        assertEquals("com.example.model", options.modelPackage().orElseThrow());
        assertEquals("com.example.invoker", options.invokerPackage().orElseThrow());
        assertEquals(path("missing/openapi/config.json"), options.config().orElseThrow());
        assertEquals(path("missing/openapi/templates"), options.templateDir().orElseThrow());
        assertFalse(options.validateSpec().orElseThrow());
        assertEquals(List.of("Case.Key", "zKey"), List.copyOf(options.options().keySet()));
        assertEquals("line one\nline two", options.options().get("Case.Key"));
        assertEquals("", options.options().get("zKey"));
        assertEquals("true", options.additionalProperties().get("hideGenerationTimestamp"));
        assertEquals("true", options.configOptions().get("useJakartaEe"));
        assertEquals("", options.globalProperties().get("models"));
        assertEquals("Instant", options.typeMappings().get("OffsetDateTime"));
        assertEquals("java.time.Instant", options.importMappings().get("Instant"));
        assertThrows(UnsupportedOperationException.class, () -> options.options().clear());
        assertThrows(UnsupportedOperationException.class, () -> presets.openApi().clear());
    }

    @Test
    void requiresKindBeforeOtherPresetFieldsAndLeavesSymbolsToTheSchema() {
        assertFailure(
                "[generated.presets.client]\ngenerator = \" \"\n",
                "Missing required manifest field `generated.presets.client.kind`.");
        assertFailure(
                "[generated.presets.client]\nkind = \"protobuf\"\n",
                "Invalid symbol `protobuf`",
                "generated.presets.client.kind");

        String family = FinalManifestGeneratedPresetFields.GENERATED_PRESET_KIND
                .symbolFamily()
                .orElseThrow();
        assertEquals(
                List.of("openapi"),
                FinalManifestSchema.registry()
                        .symbols()
                        .family(family)
                        .orElseThrow()
                        .values());
    }

    @ParameterizedTest
    @MethodSource("invalidTextFields")
    void anchorsModelOwnedTextValidationToEveryExactField(String field, String value) {
        assertFailure(
                "[generated.presets.client]\nkind = \"openapi\"\n"
                        + field + " = \"" + value + "\"\n",
                "Invalid value for `generated.presets.client." + field + "`",
                "must not be");
    }

    static Stream<Arguments> invalidTextFields() {
        return Stream.of(
                Arguments.of("generator", " "),
                Arguments.of("library", "\\n"),
                Arguments.of("apiPackage", " "),
                Arguments.of("modelPackage", "\\t"),
                Arguments.of("invokerPackage", " "));
    }

    @ParameterizedTest
    @MethodSource("invalidPaths")
    void anchorsInvalidPathsToTheirExactFields(String field, String value) {
        assertFailure(
                "[generated.presets.client]\nkind = \"openapi\"\n"
                        + field + " = \"" + value + "\"\n",
                "Invalid value for `generated.presets.client." + field + "`",
                "Invalid manifest path");
    }

    static Stream<Arguments> invalidPaths() {
        return Stream.of(
                Arguments.of("config", "/absolute/config.json"),
                Arguments.of("templateDir", "../templates"));
    }

    @ParameterizedTest
    @MethodSource("mapFields")
    void anchorsNonStringMembersForEveryStringMap(String field) {
        assertFailure(
                "[generated.presets.client]\nkind = \"openapi\"\n"
                        + field + " = { valid = \"value\", count = 2 }\n",
                "Invalid value for `generated.presets.client." + field + "`",
                "key `count`",
                "found integer");
    }

    static Stream<String> mapFields() {
        return Stream.of(
                "options",
                "additionalProperties",
                "configOptions",
                "globalProperties",
                "typeMappings",
                "importMappings");
    }

    @Test
    void validatesMapKeysAtTheirOwningFieldsButPreservesEmptyValues() {
        assertFailure(
                """
                [generated.presets.client]
                kind = "openapi"
                options = { "" = "value" }
                """,
                "Invalid value for `generated.presets.client.options`",
                "OpenAPI option key must not be blank");

        AuthoredOpenApiOptions options = preset("""
                [generated.presets.client]
                kind = "openapi"
                options = {}
                globalProperties = { models = "" }
                """);
        assertTrue(options.options().isEmpty());
        assertEquals("", options.globalProperties().get("models"));

        String nulEscape = "\\" + "u0000";
        assertFailure(
                "[generated.presets.client]\nkind = \"openapi\"\n"
                        + "options = { value = \"" + nulEscape + "\" }\n",
                "Invalid value for `generated.presets.client.options`",
                "OpenAPI option value must not contain NUL");
    }

    @Test
    void reportsEarlierCanonicalFieldFailuresBeforeLaterOnes() {
        assertFailure(
                """
                [generated.presets.client]
                kind = "openapi"
                generator = " "
                options = { bad = 2 }
                """,
                "Invalid value for `generated.presets.client.generator`");
        assertFailure(
                """
                [generated.presets.client]
                kind = "openapi"
                options = { bad = 2 }
                additionalProperties = { bad = false }
                """,
                "Invalid value for `generated.presets.client.options`");
    }

    @Test
    void leavesIdsAndEmptyNamedTablesToShapeValidation() {
        assertFailure(
                "[generated.presets.Bad_Id]\nkind = \"openapi\"\n",
                "Invalid dynamic key `Bad_Id`");
        assertFailure(
                "[generated.presets.client]\n",
                "Manifest table `[generated.presets.client]` must not be empty");
    }

    private static AuthoredOpenApiOptions preset(String source) {
        return decode(source).orElseThrow().openApi().values().iterator().next();
    }

    private static Optional<AuthoredGeneratedPresets> decode(String source) {
        return new ManifestGeneratedPresetsDecoder().decode(
                ManifestSemanticTestSupport.index(source));
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }

    private static void assertFailure(String source, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
    }
}
