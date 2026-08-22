package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProducesLane;
import sh.zolt.project.ProjectConfig;

/**
 * Generated-tool, preset, and step manifests asserted to reach the expected
 * {@link sh.zolt.project.ProjectConfig} through the final boundary, plus the derived defaults the
 * final language omits.
 */
final class ManifestGeneratedConfigAdapterTest {
    @Test
    void generatedToolsPresetsAndStepsReachTheProjectConfig() {
        ProjectConfig adapted = FinalManifests.load(
                """
                [project]
                name = "codegen"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [versions]
                jooq = "3.19.0"

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"

                [generated.tools.protobuf]
                protocCoordinate = "com.google.protobuf:protoc"
                protocVersion = "4.29.3"
                grpcCoordinate = "io.grpc:protoc-gen-grpc-java"
                grpcVersion = "1.69.0"

                [generated.tools.jooq]
                kind = "jvm"
                coordinates = [{ coordinate = "org.jooq:jooq-codegen", versionRef = "jooq" }]
                mainClass = "org.jooq.codegen.GenerationTool"

                [generated.tools.node]
                kind = "process"
                binary = "npm"
                versionCommand = ["npm", "--version"]
                versionExpect = ">=10 <11"
                allowUnpinnedTool = true

                [generated.presets.spring-client]
                kind = "openapi"
                generator = "java"
                library = "webclient"
                apiPackage = "com.example.api"
                configOptions = { useJakartaEe = "true" }

                [generated.main.public-api]
                kind = "openapi"
                input = "src/main/openapi/public-api.yaml"
                preset = "spring-client"
                modelPackage = "com.example.model"
                validateSpec = false

                [generated.main.protocol]
                kind = "protobuf"
                inputs = ["src/main/proto/service.proto"]
                javaPackage = "com.example.protocol"
                grpc = false

                [generated.main.jooq-model]
                kind = "exec"
                tool = "jooq"
                args = ["src/main/jooq/config.xml"]
                inputs = ["src/main/jooq/config.xml"]
                output = "target/generated/sources/jooq"
                produces = "java-sources"
                cache = "none"
                cwd = "tools"
                env = { NODE_ENV = "production" }
                inheritEnv = ["HTTP_PROXY"]
                timeoutSeconds = 900

                [generated.main.assets]
                kind = "exec"
                tool = "node"
                inputs = ["src/main/web"]
                output = "target/generated/resources/assets"
                produces = "resources"
                into = "static"

                [generated.test.fixtures]
                kind = "declared-root"
                inputs = ["src/test/fixtures"]
                output = "target/generated/test-sources/fixtures"
                required = false
                clean = true
                """);

        assertEquals(
                List.of("assets", "jooq-model", "protocol", "public-api"),
                adapted.build().generatedMainSources().stream().map(GeneratedSourceStep::id).toList());

        GeneratedSourceStep openApi = step(adapted.build().generatedMainSources(), "public-api");
        assertEquals(GeneratedSourceKind.OPENAPI, openApi.kind());
        assertEquals("java", openApi.language());
        assertEquals(List.of("src/main/openapi/public-api.yaml"), openApi.inputs());
        assertEquals(
                "target/generated/sources/public-api",
                openApi.output(),
                "design §13.4 derives the step output from the step id");
        assertEquals(
                Optional.of("org.openapitools:openapi-generator-cli"), openApi.openApi().toolCoordinate());
        assertEquals(Optional.of("7.11.0"), openApi.openApi().toolVersion());
        assertEquals(Optional.of("spring-client"), openApi.openApi().preset());
        assertEquals(Optional.of("java"), openApi.openApi().generator());
        assertEquals(Optional.of("webclient"), openApi.openApi().library());
        assertEquals(Optional.of("com.example.api"), openApi.openApi().apiPackage());
        assertEquals(Optional.of("com.example.model"), openApi.openApi().modelPackage());
        assertEquals(Optional.of(false), openApi.openApi().validateSpec());
        assertEquals(Map.of("useJakartaEe", "true"), openApi.openApi().configOptions());

        GeneratedSourceStep protobuf = step(adapted.build().generatedMainSources(), "protocol");
        assertEquals(GeneratedSourceKind.PROTOBUF, protobuf.kind());
        assertEquals(
                Optional.of("com.google.protobuf:protoc"), protobuf.protobuf().protocCoordinate());
        assertEquals(Optional.of("4.29.3"), protobuf.protobuf().protocVersion());
        assertEquals(
                Optional.of("io.grpc:protoc-gen-grpc-java"), protobuf.protobuf().grpcPluginCoordinate());
        assertEquals(Optional.of("1.69.0"), protobuf.protobuf().grpcPluginVersion());
        assertEquals(Optional.of("com.example.protocol"), protobuf.protobuf().javaPackage());
        assertFalse(protobuf.protobuf().grpc());

        ExecGenerationSettings jooq = step(adapted.build().generatedMainSources(), "jooq-model").exec();
        assertEquals("jooq", jooq.toolName());
        assertEquals("jvm", jooq.tool().runner());
        assertEquals("org.jooq.codegen.GenerationTool", jooq.tool().mainClass());
        assertEquals(
                List.of("org.jooq:jooq-codegen"),
                jooq.tool().coordinates().stream().map(coordinate -> coordinate.coordinate()).toList());
        assertEquals(List.of("src/main/jooq/config.xml"), jooq.args());
        assertEquals(ProducesLane.JAVA_SOURCES, jooq.produces());
        assertEquals("none", jooq.cache());
        assertEquals(Optional.of("tools"), jooq.cwd());
        assertEquals(Map.of("NODE_ENV", "production"), jooq.env());
        assertEquals(List.of("HTTP_PROXY"), jooq.inheritEnv());
        assertEquals(900, jooq.timeoutSeconds());

        ExecGenerationSettings assets = step(adapted.build().generatedMainSources(), "assets").exec();
        assertEquals("node", assets.toolName());
        assertEquals("process", assets.tool().runner());
        assertEquals("npm", assets.tool().binary());
        assertEquals(List.of("npm", "--version"), assets.tool().versionCommand());
        assertEquals(Optional.of(">=10 <11"), assets.tool().versionExpect());
        assertTrue(assets.tool().allowUnpinnedTool());
        assertEquals(ProducesLane.RESOURCES, assets.produces());
        assertEquals(Optional.of("static"), assets.into());

        GeneratedSourceStep fixtures = step(adapted.build().generatedTestSources(), "fixtures");
        assertEquals(GeneratedSourceKind.DECLARED_ROOT, fixtures.kind());
        assertEquals(List.of("src/test/fixtures"), fixtures.inputs());
        assertEquals("target/generated/test-sources/fixtures", fixtures.output());
        assertFalse(fixtures.required());
        assertTrue(fixtures.clean());
    }

    @Test
    void projectPseudoToolExecStepReachesTheProjectConfig() {
        ProjectConfig adapted = FinalManifests.load(
                """
                [project]
                name = "secrets"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [generated.main.codegen]
                kind = "exec"
                tool = "project"
                mainClass = "com.example.Codegen"
                inputs = ["src/main/codegen"]
                output = "target/generated/sources/codegen"
                produces = "java-sources"
                cache = "none"
                secretEnv = { DB_PASSWORD = "CODEGEN_DB_PASSWORD" }
                """);

        ExecGenerationSettings exec = step(adapted.build().generatedMainSources(), "codegen").exec();
        assertEquals("project", exec.toolName());
        assertEquals("com.example.Codegen", exec.tool().mainClass());
        assertEquals(ProducesLane.JAVA_SOURCES, exec.produces());
        assertEquals("none", exec.cache());
        assertEquals(Map.of("DB_PASSWORD", "CODEGEN_DB_PASSWORD"), exec.secretEnv());
    }

    @Test
    void derivedGeneratedOutputsFollowTheBuildOutputRoot() {
        ProjectConfig adapted = FinalManifests.load(
                """
                [project]
                name = "derived"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [build.output]
                root = "build"

                [generated.main.public-api]
                kind = "openapi"
                input = "src/main/openapi/public-api.yaml"

                [generated.test.fixtures-api]
                kind = "openapi"
                input = "src/test/openapi/fixtures.yaml"
                """);
        assertEquals(
                "build/generated/sources/public-api",
                adapted.build().generatedMainSources().getFirst().output(),
                "design §13.4 derives the main output from [build.output].root");
        assertEquals(
                "build/generated/test-sources/fixtures-api",
                adapted.build().generatedTestSources().getFirst().output(),
                "design §13.4 derives the test output from [build.output].root");
        assertEquals("build/generated/sources/annotations", adapted.compilerSettings().generatedSources());
        assertEquals("build/native", adapted.nativeSettings().output());
    }

    @Test
    void generatedStepsAreOrderedByStepId() {
        ProjectConfig adapted = FinalManifests.load(
                """
                [project]
                name = "ordered"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [generated.main.zeta]
                kind = "declared-root"
                inputs = ["src/main/zeta"]
                output = "target/generated/sources/zeta"

                [generated.main.alpha]
                kind = "declared-root"
                inputs = ["src/main/alpha"]
                output = "target/generated/sources/alpha"
                """);
        assertEquals(
                List.of("alpha", "zeta"),
                adapted.build().generatedMainSources().stream()
                        .map(GeneratedSourceStep::id)
                        .toList(),
                "design §5.6 and §13.9 make step order derived, not authored");
    }

    private static GeneratedSourceStep step(List<GeneratedSourceStep> steps, String id) {
        return steps.stream()
                .filter(step -> step.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no generated step " + id + " in " + steps));
    }
}
