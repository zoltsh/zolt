package sh.zolt.build.generatedsource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OpenApiGeneratedSourceValidatorTest {
    @TempDir
    private Path projectDir;

    @Test
    void acceptsValidOpenApiStep() throws IOException {
        write("src/main/openapi/public-api.yaml");
        write("src/main/openapi/config.yaml");
        Files.createDirectories(projectDir.resolve("src/main/openapi/templates"));

        assertDoesNotThrow(() -> OpenApiGeneratedSourceValidator.validateStep(
                projectDir,
                "main",
                step(validSettings(
                        Optional.of("src/main/openapi/config.yaml"),
                        Optional.of("src/main/openapi/templates")))));
    }

    @Test
    void rejectsUnsupportedLanguage() throws IOException {
        write("src/main/openapi/public-api.yaml");

        BuildException exception = assertThrows(
                BuildException.class,
                () -> OpenApiGeneratedSourceValidator.validateStep(
                        projectDir,
                        "main",
                        new GeneratedSourceStep(
                                "public-api",
                                GeneratedSourceKind.OPENAPI,
                                "kotlin",
                                "target/generated/sources/openapi/public-api",
                                java.util.List.of("src/main/openapi/public-api.yaml"),
                                true,
                                false,
                                validSettings(Optional.empty(), Optional.empty()))));

        assertTrue(exception.getMessage().contains("uses unsupported language `kotlin`"));
        assertTrue(exception.getMessage().contains("[generated.main.public-api]"));
    }

    @Test
    void rejectsMissingInputSpec() {
        BuildException exception = assertThrows(
                BuildException.class,
                () -> OpenApiGeneratedSourceValidator.validateStep(
                        projectDir,
                        "main",
                        step(validSettings(Optional.empty(), Optional.empty()))));

        assertTrue(exception.getMessage().contains("OpenAPI input src/main/openapi/public-api.yaml does not exist"));
        assertTrue(exception.getMessage().contains("Add the file or remove the generated-source step."));
    }

    @Test
    void rejectsMissingConfigAndTemplateDirectory() throws IOException {
        write("src/main/openapi/public-api.yaml");

        BuildException configException = assertThrows(
                BuildException.class,
                () -> OpenApiGeneratedSourceValidator.validateStep(
                        projectDir,
                        "main",
                        step(validSettings(Optional.of("src/main/openapi/missing.yaml"), Optional.empty()))));
        BuildException templateException = assertThrows(
                BuildException.class,
                () -> OpenApiGeneratedSourceValidator.validateStep(
                        projectDir,
                        "main",
                        step(validSettings(Optional.empty(), Optional.of("src/main/openapi/templates")))));

        assertTrue(configException.getMessage().contains("OpenAPI config `src/main/openapi/missing.yaml` does not exist"));
        assertTrue(templateException.getMessage().contains("OpenAPI templateDir `src/main/openapi/templates` does not exist"));
    }

    @Test
    void rejectsMissingToolOrGeneratorSettings() throws IOException {
        write("src/main/openapi/public-api.yaml");

        BuildException toolException = assertThrows(
                BuildException.class,
                () -> OpenApiGeneratedSourceValidator.validateStep(
                        projectDir,
                        "main",
                        step(OpenApiGenerationSettings.empty())));
        BuildException generatorException = assertThrows(
                BuildException.class,
                () -> OpenApiGeneratedSourceValidator.validateStep(
                        projectDir,
                        "main",
                        step(new OpenApiGenerationSettings(
                                Optional.of("org.openapitools:openapi-generator-cli"),
                                Optional.of("7.11.0"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of()))));

        assertTrue(toolException.getMessage().contains("[generated.tools.openapi].coordinate and version"));
        assertTrue(generatorException.getMessage().contains("requires generator or preset.generator"));
    }

    private GeneratedSourceStep step(OpenApiGenerationSettings settings) {
        return new GeneratedSourceStep(
                "public-api",
                GeneratedSourceKind.OPENAPI,
                "java",
                "target/generated/sources/openapi/public-api",
                java.util.List.of("src/main/openapi/public-api.yaml"),
                true,
                false,
                settings);
    }

    private static OpenApiGenerationSettings validSettings(Optional<String> config, Optional<String> templateDir) {
        return new OpenApiGenerationSettings(
                Optional.of("org.openapitools:openapi-generator-cli"),
                Optional.of("7.11.0"),
                Optional.empty(),
                Optional.of("spring"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                config,
                templateDir,
                Optional.empty(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
    }

    private void write(String relativePath) throws IOException {
        Path path = projectDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "content");
    }
}
