package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredExecStep;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredOpenApiStep;
import sh.zolt.manifest.authored.AuthoredProtobufStep;
import sh.zolt.toml.ZoltConfigException;

final class ManifestGeneratedSourcesDecoderTest {
    @Test
    void preservesCompleteOmissionAndAnyExplicitEmptyCollectionPresence() {
        assertTrue(decode("").isEmpty());

        for (String source : List.of(
                "[generated.tools]\n",
                "[generated.presets]\n",
                "[generated.main]\n",
                "[generated.test]\n")) {
            AuthoredGeneratedSources generated = decode(source).orElseThrow();
            assertTrue(generated.tools().declarations().isEmpty());
            assertTrue(generated.presets().openApi().isEmpty());
            assertTrue(generated.main().isEmpty());
            assertTrue(generated.test().isEmpty());
        }

        AuthoredGeneratedSources explicit = decode("""
                [generated.tools]
                [generated.presets]
                [generated.main]
                [generated.test]
                """).orElseThrow();
        assertThrows(UnsupportedOperationException.class, explicit.tools().declarations()::clear);
        assertThrows(UnsupportedOperationException.class, explicit.presets().openApi()::clear);
        assertThrows(UnsupportedOperationException.class, explicit.main()::clear);
        assertThrows(UnsupportedOperationException.class, explicit.test()::clear);
    }

    @Test
    void composesToolsPresetsAndBothSortedImmutableStepLanes() {
        AuthoredGeneratedSources generated = decode(completeDomain()).orElseThrow();

        assertEquals(
                List.of("jooq", "legacy-openapi"),
                generated.tools().declarations().keySet().stream()
                        .map(LocalId::value)
                        .toList());
        assertEquals(
                List.of("spring-client"),
                generated.presets().openApi().keySet().stream()
                        .map(LocalId::value)
                        .toList());
        assertEquals(
                List.of("a-model", "z-api"),
                generated.main().keySet().stream().map(LocalId::value).toList());
        assertEquals(
                List.of("a-root", "z-protocol"),
                generated.test().keySet().stream().map(LocalId::value).toList());
        assertInstanceOf(AuthoredExecStep.class, generated.main().get(id("a-model")));
        assertInstanceOf(AuthoredOpenApiStep.class, generated.main().get(id("z-api")));
        assertInstanceOf(AuthoredProtobufStep.class, generated.test().get(id("z-protocol")));
        assertThrows(UnsupportedOperationException.class, generated.main()::clear);
        assertThrows(UnsupportedOperationException.class, generated.test()::clear);
    }

    @Test
    void acceptsImplicitBuiltInToolsWithoutDeclarationsOrMaterializedDefaults() {
        AuthoredGeneratedSources generated = decode("""
                [generated.main.api]
                kind = "openapi"
                input = "src/main/openapi/api.yaml"

                [generated.test.protocol]
                kind = "protobuf"
                inputs = ["src/test/proto/service.proto"]
                """).orElseThrow();

        AuthoredOpenApiStep api = assertInstanceOf(
                AuthoredOpenApiStep.class, generated.main().get(id("api")));
        AuthoredProtobufStep protocol = assertInstanceOf(
                AuthoredProtobufStep.class, generated.test().get(id("protocol")));
        assertTrue(generated.tools().declarations().isEmpty());
        assertTrue(generated.presets().openApi().isEmpty());
        assertTrue(api.tool().isEmpty());
        assertTrue(api.output().isEmpty());
        assertTrue(protocol.tool().isEmpty());
        assertTrue(protocol.output().isEmpty());
    }

    @Test
    void anchorsExplicitToolAndPresetFailuresToTheirExactFields() {
        assertFailure("""
                [generated.main.api]
                kind = "openapi"
                tool = "missing-tool"
                input = "api.yaml"
                preset = "missing-preset"
                """, "`generated.main.api.tool`", "declared OpenAPI tool");
        assertFailure("""
                [generated.main.api]
                kind = "openapi"
                input = "api.yaml"
                preset = "missing-preset"
                """, "`generated.main.api.preset`", "undefined preset");
        assertFailure("""
                [generated.tools.wrong-kind]
                kind = "openapi"

                [generated.main.protocol]
                kind = "protobuf"
                tool = "wrong-kind"
                inputs = ["service.proto"]
                """, "`generated.main.protocol.tool`", "declared Protobuf tool");
        assertFailure("""
                [generated.main.model]
                kind = "exec"
                tool = "missing-tool"
                inputs = ["schema.sql"]
                output = "target/generated/model"
                produces = "java-sources"
                """, "`generated.main.model.tool`", "declared JVM or process tool");
    }

    @Test
    void validatesReferencesMainThenTestAndBeforeTheNextSourceRow() {
        assertFailure("""
                [generated.test.a-test]
                kind = "exec"
                tool = "missing-test"
                inputs = ["test.sql"]
                output = "target/generated/test"
                produces = "test-sources"

                [generated.main.z-first]
                kind = "exec"
                tool = "missing-main"
                inputs = ["main.sql"]
                output = "target/generated/main"
                produces = "java-sources"

                [generated.main.a-later]
                kind = "exec"
                tool = "project"
                inputs = ["later.sql"]
                output = "target/generated/later"
                produces = "java-sources"
                """, "`generated.main.z-first.tool`", "missing-main");
    }

    @Test
    void reportsSameRowLeafFailureBeforeItsUndefinedReference() {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode("""
                        [generated.main.model]
                        kind = "exec"
                        tool = "missing-tool"
                        inputs = []
                        output = "target/generated/model"
                        produces = "java-sources"
                        """));

        assertTrue(
                failure.getMessage().contains("`generated.main.model.inputs`"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains(".tool`"), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    @Test
    void propagatesChildFailuresInToolsPresetsThenStepsOrder() {
        assertFailure("""
                [generated.tools.process]
                kind = "process"
                binary = "npm"
                versionCommand = ["npm", "--version"]
                allowUnpinnedTool = false

                [generated.presets.invalid]
                kind = "openapi"
                generator = " "

                [generated.main.invalid]
                kind = "exec"
                tool = "missing"
                """, "`generated.tools.process.allowUnpinnedTool`", "allowUnpinnedTool = true");
        assertFailure("""
                [generated.presets.invalid]
                kind = "openapi"
                generator = " "

                [generated.main.invalid]
                kind = "exec"
                tool = "project"
                """, "`generated.presets.invalid.generator`", "must not be blank");
    }

    private static Optional<AuthoredGeneratedSources> decode(String source) {
        return new ManifestGeneratedSourcesDecoder().decode(
                ManifestSemanticTestSupport.index(source), ignored -> {});
    }

    private static void assertFailure(String source, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }

    private static String completeDomain() {
        return """
                [generated.tools.legacy-openapi]
                kind = "openapi"
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"

                [generated.tools.jooq]
                kind = "jvm"
                coordinates = [
                    { coordinate = "org.jooq:jooq-codegen", version = "3.20.1" },
                ]
                mainClass = "org.jooq.codegen.GenerationTool"

                [generated.presets.spring-client]
                kind = "openapi"
                generator = "java"

                [generated.main.z-api]
                kind = "openapi"
                tool = "legacy-openapi"
                input = "src/main/openapi/api.yaml"
                preset = "spring-client"

                [generated.main.a-model]
                kind = "exec"
                tool = "jooq"
                inputs = ["src/main/resources/schema.sql"]
                output = "target/generated/jooq"
                produces = "java-sources"

                [generated.test.z-protocol]
                kind = "protobuf"
                inputs = ["src/test/proto/service.proto"]

                [generated.test.a-root]
                kind = "declared-root"
                inputs = ["src/test/fixtures"]
                output = "target/generated/test-fixtures"
                """;
    }
}
