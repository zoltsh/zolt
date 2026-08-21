package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;

/**
 * Generated-tool, preset, and step manifest pairs asserted to produce the same legacy
 * {@link sh.zolt.project.ProjectConfig}, plus the derived defaults the final language omits.
 */
final class ManifestGeneratedConfigEquivalenceTest {
    @Test
    void generatedToolsAndStepsPairIsEquivalent() {
        FinalManifestPairs.assertEquivalent(
                """
                [project]
                name = "codegen"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [versions]
                jooq = "3.19.0"

                [generated.openapiTool]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"

                [generated.protobufTool]
                protocCoordinate = "com.google.protobuf:protoc"
                protocVersion = "4.29.3"
                grpcPluginCoordinate = "io.grpc:protoc-gen-grpc-java"
                grpcPluginVersion = "1.69.0"

                [generated.openapiPresets.spring-client]
                generator = "java"
                library = "webclient"
                apiPackage = "com.example.api"
                configOptions = { useJakartaEe = "true" }

                [generated.execTools.jooq]
                runner = "jvm"
                coordinates = [{ coordinate = "org.jooq:jooq-codegen", versionRef = "jooq" }]
                mainClass = "org.jooq.codegen.GenerationTool"

                [generated.execTools.node]
                runner = "process"
                binary = "npm"
                versionCommand = ["npm", "--version"]
                versionExpect = ">=10 <11"
                allowUnpinnedTool = true

                [generated.main.assets]
                kind = "exec"
                language = "java"
                tool = "node"
                inputs = ["src/main/web"]
                output = "target/generated/resources/assets"
                produces = "resources"
                into = "static"

                [generated.main.jooq-model]
                kind = "exec"
                language = "java"
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

                [generated.main.protocol]
                kind = "protobuf"
                language = "java"
                inputs = ["src/main/proto/service.proto"]
                output = "target/generated/sources/protocol"
                javaPackage = "com.example.protocol"
                grpc = false

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/public-api"
                preset = "spring-client"
                modelPackage = "com.example.model"
                validateSpec = false

                [generated.test.fixtures]
                kind = "declared-root"
                language = "java"
                inputs = ["src/test/fixtures"]
                output = "target/generated/test-sources/fixtures"
                required = false
                clean = true
                """,
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
    }

    @Test
    void projectPseudoToolExecStepPairIsEquivalent() {
        FinalManifestPairs.assertEquivalent(
                """
                [project]
                name = "secrets"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [generated.main.codegen]
                kind = "exec"
                language = "java"
                tool = "project"
                mainClass = "com.example.Codegen"
                inputs = ["src/main/codegen"]
                output = "target/generated/sources/codegen"
                produces = "java-sources"
                cache = "none"
                secretEnv = { DB_PASSWORD = "CODEGEN_DB_PASSWORD" }
                """,
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
    }

    @Test
    void derivedGeneratedOutputsFollowTheBuildOutputRoot() {
        ProjectConfig adapted = FinalManifestPairs.loader().load(
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
        ProjectConfig adapted = FinalManifestPairs.loader().load(
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
}
