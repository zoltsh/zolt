package sh.zolt.build.generatedsource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OpenApiGeneratedSourceCacheTest {
    private final OpenApiGeneratedSourceCache cache = new OpenApiGeneratedSourceCache();

    @TempDir
    private Path projectDir;

    @Test
    void reportsCurrentOutputOnlyWhenFingerprintMatches() throws IOException {
        writeInputs("openapi: 3.1.0\n");
        GeneratedSourceStep step = step();
        Path output = projectDir.resolve(step.output());
        Files.createDirectories(output);
        OpenApiGeneratedSourceCache.GenerationCacheState state = cache.state(
                projectDir,
                output,
                toolClasspath(),
                "main",
                step);

        assertFalse(cache.isCurrent(output, state));

        cache.writeFingerprint(state);

        assertTrue(cache.isCurrent(output, state));

        Files.writeString(state.fingerprint(), "stale");

        assertFalse(cache.isCurrent(output, state));
    }

    @Test
    void fingerprintChangesWhenSpecToolConfigOrTemplateChanges() throws IOException {
        writeInputs("openapi: 3.1.0\n");
        GeneratedSourceStep step = step();
        Path output = projectDir.resolve(step.output());
        String original = cache.state(projectDir, output, toolClasspath(), "main", step).fingerprintSha256();

        Files.writeString(projectDir.resolve("src/main/openapi/public-api.yaml"), "openapi: 3.1.0\ninfo:\n  title: Changed\n");
        String specChanged = cache.state(projectDir, output, toolClasspath(), "main", step).fingerprintSha256();

        Files.writeString(projectDir.resolve("cache/openapi-generator-cli.jar"), "tool-changed\n");
        String toolChanged = cache.state(projectDir, output, toolClasspath(), "main", step).fingerprintSha256();

        Files.writeString(projectDir.resolve("src/main/openapi/config.yaml"), "enumUnknownDefaultCase: true\n");
        String configChanged = cache.state(projectDir, output, toolClasspath(), "main", step).fingerprintSha256();

        Files.writeString(projectDir.resolve("src/main/openapi/templates/api.mustache"), "{{changed}}\n");
        String templateChanged = cache.state(projectDir, output, toolClasspath(), "main", step).fingerprintSha256();

        assertNotEquals(original, specChanged);
        assertNotEquals(specChanged, toolChanged);
        assertNotEquals(toolChanged, configChanged);
        assertNotEquals(configChanged, templateChanged);
    }

    @Test
    void writesLogBesideFingerprint() throws IOException {
        writeInputs("openapi: 3.1.0\n");
        GeneratedSourceStep step = step();
        Path output = projectDir.resolve(step.output());
        Files.createDirectories(output);
        OpenApiGeneratedSourceCache.GenerationCacheState state = cache.state(
                projectDir,
                output,
                toolClasspath(),
                "main",
                step);

        cache.writeLog(state, "generated\n");

        assertEquals(output.resolve(".zolt-openapi-main-public-api.log"), state.log());
        assertEquals("generated\n", Files.readString(state.log()));
    }

    private void writeInputs(String spec) throws IOException {
        Files.createDirectories(projectDir.resolve("src/main/openapi/templates"));
        Files.writeString(projectDir.resolve("src/main/openapi/public-api.yaml"), spec);
        Files.writeString(projectDir.resolve("src/main/openapi/config.yaml"), "hideGenerationTimestamp: true\n");
        Files.writeString(projectDir.resolve("src/main/openapi/templates/api.mustache"), "{{api}}\n");
        Files.createDirectories(projectDir.resolve("cache"));
        Files.writeString(projectDir.resolve("cache/openapi-generator-cli.jar"), "tool\n");
    }

    private List<Path> toolClasspath() {
        return List.of(projectDir.resolve("cache/openapi-generator-cli.jar"));
    }

    private static GeneratedSourceStep step() {
        ProjectConfig config = new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                generator = "spring"
                config = "src/main/openapi/config.yaml"
                templateDir = "src/main/openapi/templates"
                additionalProperties = { sourceFolder = "." }
                """);
        return config.build().generatedMainSources().getFirst();
    }
}
