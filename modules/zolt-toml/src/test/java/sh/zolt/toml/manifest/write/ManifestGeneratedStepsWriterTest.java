package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.tomlj.Toml;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.GeneratedCachePolicy;
import sh.zolt.manifest.GeneratedLanguage;
import sh.zolt.manifest.GeneratedOutputKind;
import sh.zolt.manifest.GeneratedStepSettings;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ResourceGlob;
import sh.zolt.manifest.authored.AuthoredDeclaredRootStep;
import sh.zolt.manifest.authored.AuthoredExecStep;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredGeneratedStep;
import sh.zolt.manifest.authored.AuthoredOpenApiOptions;
import sh.zolt.manifest.authored.AuthoredOpenApiStep;
import sh.zolt.manifest.authored.AuthoredProtobufStep;

final class ManifestGeneratedStepsWriterTest {
    private static final String DECLARATIONS = """
            [generated.tools.custom-openapi]
            kind = "openapi"

            [generated.tools.custom-protobuf]
            kind = "protobuf"

            [generated.tools.process]
            kind = "process"
            binary = "npm"
            versionCommand = ["npm", "--version"]
            allowUnpinnedTool = true

            [generated.presets.shared]
            kind = "openapi"

            """;

    @ParameterizedTest
    @MethodSource("lanes")
    void emitsEveryTypedStepFieldForBothLanesAndRoundTrips(Lane lane) {
        Map<LocalId, AuthoredGeneratedStep> steps = completeSteps();

        String output = write(lane, steps);

        assertEquals(expected(lane), output);
        assertFalse(Toml.parse(output).hasErrors());
        AuthoredGeneratedSources decoded = decode(lane, output);
        assertEquals(output, write(lane, lane.steps(decoded)));
    }

    @Test
    void omitsFrozenLanguageBooleanToolAndCacheDefaults() {
        GeneratedStepSettings producerDefaults = settings(true, true);
        Map<LocalId, AuthoredGeneratedStep> steps = Map.of(
                id("openapi"), new AuthoredOpenApiStep(
                        producerDefaults,
                        Optional.of(id("openapi")),
                        glob("api.yaml"),
                        Optional.empty(),
                        Optional.empty(),
                        withValidateSpec(true)),
                id("protobuf"), new AuthoredProtobufStep(
                        producerDefaults,
                        Optional.of(id("protobuf")),
                        List.of(glob("schema.proto")),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(true)),
                id("project"), new AuthoredExecStep(
                        producerDefaults,
                        id("project"),
                        Optional.of(new JavaBinaryClassName("org.example.Generator")),
                        List.of(),
                        List.of(glob("config.json")),
                        path("target/generated/project"),
                        GeneratedOutputKind.JAVA_SOURCES,
                        Optional.empty(),
                        Optional.of(GeneratedCachePolicy.CONTENT),
                        Optional.empty(),
                        Map.of(),
                        Map.of(),
                        List.of(),
                        Optional.empty()),
                id("root"), new AuthoredDeclaredRootStep(
                        settings(true, false),
                        List.of(glob("external")),
                        path("target/generated/external")));

        String output = write(Lane.MAIN, steps);

        assertEquals(
                """
                [generated.main.openapi]
                kind = "openapi"
                input = "api.yaml"

                [generated.main.project]
                kind = "exec"
                tool = "project"
                mainClass = "org.example.Generator"
                inputs = ["config.json"]
                output = "target/generated/project"
                produces = "java-sources"

                [generated.main.protobuf]
                kind = "protobuf"
                inputs = ["schema.proto"]

                [generated.main.root]
                kind = "declared-root"
                inputs = ["external"]
                output = "target/generated/external"
                """,
                output);
        assertFalse(output.contains("language ="));
        assertFalse(output.contains("required ="));
        assertFalse(output.contains("clean ="));
        assertFalse(output.contains("grpc ="));
        assertFalse(output.contains("validateSpec ="));
        assertFalse(output.contains("cache ="));
    }

    @Test
    void wrapsEveryLongDirectExecArrayAndKeepsItemsOnOneLine() {
        String argument = "a".repeat(91);
        String input = "i".repeat(91);
        EnvironmentVariableName inherited = env("A_" + "X".repeat(84));
        AuthoredExecStep step = new AuthoredExecStep(
                GeneratedStepSettings.defaultsOmitted(),
                id("process"),
                Optional.empty(),
                List.of(argument),
                List.of(glob(input)),
                path("target/generated/long"),
                GeneratedOutputKind.JAVA_SOURCES,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                List.of(inherited),
                Optional.empty());

        String output = write(Lane.MAIN, Map.of(id("long"), step));

        assertTrue(output.contains("args = [\n    \"" + argument + "\",\n]"));
        assertTrue(output.contains("inputs = [\n    \"" + input + "\",\n]"));
        assertTrue(output.contains(
                "inheritEnv = [\n    \"" + inherited.value() + "\",\n]"));
        AuthoredGeneratedSources decoded = decode(Lane.MAIN, output);
        assertEquals(output, write(Lane.MAIN, decoded.main()));
    }

    @ParameterizedTest
    @MethodSource("lanes")
    void omitsExplicitOutputsEqualToTheLaneDerivedDefault(Lane lane) {
        ManifestRelativePath root = path("custom-output");
        String directory = lane == Lane.MAIN ? "generated/sources" : "generated/test-sources";
        AuthoredOpenApiStep step = new AuthoredOpenApiStep(
                GeneratedStepSettings.defaultsOmitted(),
                Optional.empty(),
                glob("api.yaml"),
                Optional.of(path(root.value() + "/" + directory + "/client")),
                Optional.empty(),
                AuthoredOpenApiOptions.empty());

        String output = write(lane, Map.of(id("client"), step), root);

        assertFalse(output.contains("output ="));
        assertFalse(Toml.parse(output).hasErrors());
    }

    static Stream<Lane> lanes() {
        return Stream.of(Lane.values());
    }

    private static Map<LocalId, AuthoredGeneratedStep> completeSteps() {
        return Map.of(
                id("a-openapi"), new AuthoredOpenApiStep(
                        settings(false, false),
                        Optional.of(id("custom-openapi")),
                        glob("openapi/spec.yaml"),
                        Optional.of(path("target/generated/openapi")),
                        Optional.of(id("shared")),
                        openApiOptions()),
                id("b-protobuf"), new AuthoredProtobufStep(
                        settings(true, true),
                        Optional.of(id("custom-protobuf")),
                        List.of(glob("proto/z.proto"), glob("proto/a.proto")),
                        Optional.of(path("target/generated/protobuf")),
                        Optional.of("org.example.proto"),
                        Optional.of(false)),
                id("c-exec"), new AuthoredExecStep(
                        GeneratedStepSettings.defaultsOmitted(),
                        id("process"),
                        Optional.empty(),
                        List.of("generate", "--all"),
                        List.of(glob("schema/z.sql"), glob("schema/a.sql")),
                        path("target/generated/resources"),
                        GeneratedOutputKind.RESOURCES,
                        Optional.of(path("generated")),
                        Optional.of(GeneratedCachePolicy.NONE),
                        Optional.of(path("tools")),
                        Map.of(env("Z_MODE"), "last", env("A_MODE"), "first"),
                        Map.of(env("DB_PASSWORD"), env("CODEGEN_DB_PASSWORD")),
                        List.of(env("Z_PROXY"), env("A_PROXY")),
                        Optional.of(30)),
                id("d-project"), new AuthoredExecStep(
                        GeneratedStepSettings.defaultsOmitted(),
                        id("project"),
                        Optional.of(new JavaBinaryClassName("org.example.ProjectGenerator")),
                        List.of(),
                        List.of(glob("project/config.json")),
                        path("target/generated/project"),
                        GeneratedOutputKind.JAVA_SOURCES,
                        Optional.empty(),
                        Optional.of(GeneratedCachePolicy.CONTENT),
                        Optional.empty(), Map.of(), Map.of(), List.of(), Optional.empty()),
                id("e-root"), new AuthoredDeclaredRootStep(
                        settings(false, true),
                        List.of(glob("fixtures/z"), glob("fixtures/a")),
                        path("target/generated/fixtures")));
    }

    private static AuthoredOpenApiOptions openApiOptions() {
        return new AuthoredOpenApiOptions(
                Optional.of("java"), Optional.of("webclient"),
                Optional.of("org.example.api"), Optional.of("org.example.model"),
                Optional.of("org.example.invoker"), Optional.of(path("openapi/config.json")),
                Optional.of(path("openapi/templates")), Optional.of(false),
                Map.of("zKey", "", "Case.Key", "value"),
                Map.of("hideGenerationTimestamp", "true"),
                Map.of("useJakartaEe", "true"), Map.of("models", ""),
                Map.of("OffsetDateTime", "Instant"),
                Map.of("Instant", "java.time.Instant"));
    }

    private static AuthoredOpenApiOptions withValidateSpec(boolean value) {
        return new AuthoredOpenApiOptions(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(value),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static String expected(Lane lane) {
        return """
                [generated.<lane>.a-openapi]
                kind = "openapi"
                tool = "custom-openapi"
                input = "openapi/spec.yaml"
                output = "target/generated/openapi"
                preset = "shared"
                generator = "java"
                library = "webclient"
                apiPackage = "org.example.api"
                modelPackage = "org.example.model"
                invokerPackage = "org.example.invoker"
                config = "openapi/config.json"
                templateDir = "openapi/templates"
                validateSpec = false
                options = { "Case.Key" = "value", zKey = "" }
                additionalProperties = { hideGenerationTimestamp = "true" }
                configOptions = { useJakartaEe = "true" }
                globalProperties = { models = "" }
                typeMappings = { OffsetDateTime = "Instant" }
                importMappings = { Instant = "java.time.Instant" }
                required = false
                clean = false

                [generated.<lane>.b-protobuf]
                kind = "protobuf"
                tool = "custom-protobuf"
                inputs = ["proto/z.proto", "proto/a.proto"]
                output = "target/generated/protobuf"
                javaPackage = "org.example.proto"
                grpc = false

                [generated.<lane>.c-exec]
                kind = "exec"
                tool = "process"
                args = ["generate", "--all"]
                inputs = ["schema/z.sql", "schema/a.sql"]
                output = "target/generated/resources"
                produces = "resources"
                into = "generated"
                cache = "none"
                cwd = "tools"
                env = { A_MODE = "first", Z_MODE = "last" }
                secretEnv = { DB_PASSWORD = "CODEGEN_DB_PASSWORD" }
                inheritEnv = ["A_PROXY", "Z_PROXY"]
                timeoutSeconds = 30

                [generated.<lane>.d-project]
                kind = "exec"
                tool = "project"
                mainClass = "org.example.ProjectGenerator"
                inputs = ["project/config.json"]
                output = "target/generated/project"
                produces = "java-sources"

                [generated.<lane>.e-root]
                kind = "declared-root"
                inputs = ["fixtures/z", "fixtures/a"]
                output = "target/generated/fixtures"
                required = false
                clean = true
                """.replace("<lane>", lane.segment());
    }

    private static AuthoredGeneratedSources decode(Lane lane, String output) {
        return decodeAuthoredManifest(
                        "[project]\nname = \"round-trip\"\n\n" + DECLARATIONS + output)
                .generated()
                .orElseThrow();
    }

    private static String write(Lane lane, Map<LocalId, AuthoredGeneratedStep> steps) {
        return write(lane, steps, path("target"));
    }

    private static String write(
            Lane lane,
            Map<LocalId, AuthoredGeneratedStep> steps,
            ManifestRelativePath outputRoot) {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        new ManifestGeneratedStepsWriter().write(
                emitter,
                lane == Lane.MAIN ? steps : Map.of(),
                lane == Lane.TEST ? steps : Map.of(),
                outputRoot);
        return emitter.finish();
    }

    private static GeneratedStepSettings settings(boolean required, boolean clean) {
        return new GeneratedStepSettings(
                Optional.of(GeneratedLanguage.JAVA), Optional.of(required), Optional.of(clean));
    }

    private static EnvironmentVariableName env(String value) {
        return new EnvironmentVariableName(value);
    }

    private static ResourceGlob glob(String value) {
        return new ResourceGlob(value);
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }

    private enum Lane {
        MAIN("main"), TEST("test");

        private final String segment;

        Lane(String segment) {
            this.segment = segment;
        }

        String segment() {
            return segment;
        }

        Map<LocalId, AuthoredGeneratedStep> steps(AuthoredGeneratedSources generated) {
            return this == MAIN ? generated.main() : generated.test();
        }
    }
}
