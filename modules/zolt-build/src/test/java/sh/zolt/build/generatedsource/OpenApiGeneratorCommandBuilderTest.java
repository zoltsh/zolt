package sh.zolt.build.generatedsource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OpenApiGeneratorCommandBuilderTest {
    @Test
    void buildsDeterministicOpenApiGeneratorCommand(@TempDir Path projectRoot) {
        Path project = projectRoot.toAbsolutePath().normalize();
        OpenApiGeneratorCommandBuilder builder = new OpenApiGeneratorCommandBuilder(":");
        GeneratedSourceStep step = config().build().generatedMainSources().getFirst();

        List<String> command = builder.command(
                project,
                Path.of("/jdk/bin/java"),
                List.of(
                        project.resolve("cache/openapi-generator-b.jar"),
                        project.resolve("cache/openapi-generator-a.jar")),
                "main",
                step);

        assertEquals("/jdk/bin/java", command.getFirst());
        assertEquals(
                project.resolve("cache/openapi-generator-a.jar")
                        + ":"
                        + project.resolve("cache/openapi-generator-b.jar"),
                optionValue(command, "-cp"));
        assertTrue(command.contains("org.openapitools.codegen.OpenAPIGenerator"));
        assertTrue(command.contains("generate"));
        assertEquals(
                project.resolve("src/main/openapi/public-api.yaml").toString(),
                optionValue(command, "--input-spec"));
        assertEquals(
                project.resolve("target/generated/sources/openapi/public-api").toString(),
                optionValue(command, "--output"));
        assertEquals("spring", optionValue(command, "--generator-name"));
        assertEquals("spring-boot", optionValue(command, "--library"));
        assertEquals("com.example.publicapi", optionValue(command, "--api-package"));
        assertEquals("com.example.publicmodel", optionValue(command, "--model-package"));
        assertEquals("com.example.invoker", optionValue(command, "--invoker-package"));
        assertTrue(command.contains("--skip-validate-spec"));
        assertEquals(
                "additionalModelTypeAnnotations=@lombok.Builder,interfaceOnly=true,sourceFolder=.",
                optionValue(command, "--additional-properties"));
        assertEquals("apis=true,models=true", optionValue(command, "--global-property"));
        assertEquals("OffsetDateTime=Instant,UUID=String", optionValue(command, "--type-mappings"));
        assertEquals("Instant=java.time.Instant,String=java.lang.String", optionValue(command, "--import-mappings"));
    }

    private static ProjectConfig config() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"

                [generated.presets.spring-api]
                kind = "openapi"
                generator = "spring"
                library = "spring-boot"
                apiPackage = "com.example.api"
                modelPackage = "com.example.model"
                invokerPackage = "com.example.invoker"
                validateSpec = false
                additionalProperties = { sourceFolder = ".", interfaceOnly = "true" }
                configOptions = { additionalModelTypeAnnotations = "@lombok.Builder" }
                globalProperties = { models = "true", apis = "true" }
                typeMappings = { UUID = "String", OffsetDateTime = "Instant" }
                importMappings = { String = "java.lang.String", Instant = "java.time.Instant" }

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                preset = "spring-api"
                apiPackage = "com.example.publicapi"
                modelPackage = "com.example.publicmodel"
                """);
    }

    private static String optionValue(List<String> command, String option) {
        return command.get(command.indexOf(option) + 1);
    }
}
