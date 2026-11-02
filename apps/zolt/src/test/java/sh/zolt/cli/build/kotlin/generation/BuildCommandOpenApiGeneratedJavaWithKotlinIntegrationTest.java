package sh.zolt.cli.build.kotlin.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KotlinCompilerCliFixture;
import sh.zolt.cli.build.kotlin.OpenApiKotlinCliFixture;

/** Offline CLI proof for OpenAPI-owned Java consumed by Kotlin main sources. */
final class BuildCommandOpenApiGeneratedJavaWithKotlinIntegrationTest {
    private static final String INPUT = "src/main/openapi/provider.yaml";
    private static final String GENERATED_SOURCE =
            "target/generated/sources/openapi/provider/com/example/GeneratedApi.java";

    @TempDir
    private Path tempDir;

    @Test
    void repairsAndRecompilesOpenApiGeneratedJavaOffline() throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            OpenApiKotlinCliFixture.publish(repository, tempDir.resolve("fixture-work"));
            writeProject(project, repository);
            writeGeneratedApi(project, "v1");

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();

            CommandResult first = build(project, offlineCache);
            assertSuccessful(first);
            Path generatedSource = project.resolve(GENERATED_SOURCE);
            assertTrue(Files.isRegularFile(generatedSource));
            assertTrue(Files.isRegularFile(project.resolve(
                    "target/classes/com/example/GeneratedApi.class")));
            assertTrue(Files.isRegularFile(project.resolve("target/classes/com/example/Main.class")));
            assertEquals(List.of("run"), invocations(project));
            assertTiming(first, "full");

            CommandResult warm = build(project, offlineCache);
            assertSuccessful(warm);
            assertEquals(List.of("run"), invocations(project));
            assertTiming(warm, "skipped");

            Files.writeString(generatedSource, "not Java\n");
            CommandResult regenerated = build(project, offlineCache);
            assertSuccessful(regenerated);
            assertEquals(List.of("run", "run"), invocations(project));
            assertTrue(Files.readString(generatedSource).contains("\"v1\""));
            assertTiming(regenerated, "skipped");

            writeGeneratedApi(project, "v2");
            CommandResult changed = build(project, offlineCache);
            assertSuccessful(changed);
            assertEquals(List.of("run", "run", "run"), invocations(project));
            assertTiming(changed, "full");

            CommandResult run = execute(
                    "run",
                    "--cwd", project.toString(),
                    "--cache-root", offlineCache.toString());
            assertEquals(0, run.exitCode(), run.stderr());
            assertTrue(run.stdout().contains("openapi-v2"), run.stdout());
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static CommandResult build(Path project, Path cache) {
        return execute(
                "build",
                "--offline",
                "--no-build-cache",
                "--no-progress",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
    }

    private static void writeProject(Path project, CliTestRepository repository) throws IOException {
        Files.createDirectories(project.resolve("src/main/kotlin/com/example"));
        Files.createDirectories(project.resolve(INPUT).getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "openapi-java-kotlin"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "%s"

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "%s"

                [generated.main.provider]
                kind = "openapi"
                input = "%s"
                output = "target/generated/sources/openapi/provider"
                generator = "java"
                additionalProperties = { relativePath = "com/example/GeneratedApi.java", logFile = "openapi-main-invocations.txt" }

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                currentJavaMajorVersion(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                OpenApiKotlinCliFixture.VERSION,
                INPUT,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION));
        Files.writeString(project.resolve("src/main/kotlin/com/example/Main.kt"), """
                package com.example

                object Main {
                    @JvmStatic
                    fun main(args: Array<String>) {
                        println("openapi-" + GeneratedApi.value())
                    }
                }
                """);
    }

    private static void writeGeneratedApi(Path project, String value) throws IOException {
        Files.writeString(project.resolve(INPUT), """
                package com.example;

                public final class GeneratedApi {
                    private GeneratedApi() {}

                    public static String value() {
                        return "%s";
                    }
                }
                """.formatted(value));
    }

    private static List<String> invocations(Path project) throws IOException {
        return Files.readAllLines(project.resolve("openapi-main-invocations.txt"));
    }

    private static void assertSuccessful(CommandResult result) {
        assertEquals(
                0,
                result.exitCode(),
                () -> "stderr:\n" + result.stderr() + "\nstdout:\n" + result.stdout());
    }

    private static void assertTiming(CommandResult result, String mode) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile main\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing compile timing in:\n" + result.stderr()));
        assertTrue(line.contains("\"mainCompilationMode\":\"" + mode + "\""), line);
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
