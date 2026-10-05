package sh.zolt.cli.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Canonical CLI/worker proof for OpenAPI-owned Kotlin test sources. */
final class OpenApiGeneratedKotlinTestIntegrationTest {
    private static final String GENERATED_SOURCE =
            "target/generated/test-sources/openapi/client/com/example/GeneratedOpenApiTest.kt";

    @TempDir
    private Path tempDir;

    @Test
    void generatesCompilesRunsRepairsAndInvalidatesKotlinTestsOffline() throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            OpenApiKotlinCliFixture.publish(repository, tempDir.resolve("fixture-work"));
            writeProject(project, repository.baseUri());

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult first = test(project, offlineCache);
            Path generatedSource = project.resolve(GENERATED_SOURCE);
            Path generatedClass = project.resolve(
                    "target/test-classes/com/example/GeneratedOpenApiTest.class");
            assertSuccessful(first);
            assertTiming(first, "\"testCompilationMode\":\"full\"");
            assertTrue(Files.isRegularFile(generatedSource));
            assertTrue(Files.isRegularFile(generatedClass));
            byte[] initialSource = Files.readAllBytes(generatedSource);
            byte[] initialClass = Files.readAllBytes(generatedClass);
            FileTime initialClassTime = Files.getLastModifiedTime(generatedClass);
            assertEquals(List.of("run"), invocations(project));

            CommandResult warm = test(project, offlineCache);
            assertSuccessful(warm);
            assertTiming(warm, "\"testCompilationMode\":\"skipped\"");
            assertEquals(List.of("run"), invocations(project));
            assertArrayEquals(initialClass, Files.readAllBytes(generatedClass));
            assertEquals(initialClassTime, Files.getLastModifiedTime(generatedClass));

            Files.writeString(generatedSource, "not Kotlin\n");
            CommandResult repaired = test(project, offlineCache);
            assertSuccessful(repaired);
            assertTiming(repaired, "\"testCompilationMode\":\"skipped\"");
            assertArrayEquals(initialSource, Files.readAllBytes(generatedSource));
            assertArrayEquals(initialClass, Files.readAllBytes(generatedClass));
            assertEquals(List.of("run", "run"), invocations(project));

            Files.writeString(
                    project.resolve("src/test/openapi/client.yaml"),
                    generatedTestSource("v2"));
            CommandResult changed = test(project, offlineCache);
            assertSuccessful(changed);
            assertTiming(changed, "\"testCompilationMode\":\"full\"");
            assertFalse(Arrays.equals(initialSource, Files.readAllBytes(generatedSource)));
            assertFalse(Arrays.equals(initialClass, Files.readAllBytes(generatedClass)));
            assertEquals(List.of("run", "run", "run"), invocations(project));
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static CommandResult test(Path project, Path cache) {
        return execute(
                "test",
                "--no-build-cache",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
    }

    private static void assertSuccessful(CommandResult result) {
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Tests passed"), result.stdout());
        assertTrue(result.stdout().matches("(?s).*\\b1 tests successful\\b.*"), result.stdout());
    }

    private static void assertTiming(CommandResult result, String expected) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile test sources\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing compile test sources timing in:\n" + result.stderr()));
        assertTrue(line.contains(expected), line);
    }

    private static List<String> invocations(Path project) throws IOException {
        return Files.readAllLines(project.resolve("openapi-test-invocations.txt"));
    }

    private static void writeProject(Path project, URI repository) throws IOException {
        Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.createDirectories(project.resolve("src/test/openapi"));
        Files.writeString(project.resolve("src/main/java/com/example/Main.java"), """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "main";
                    }
                }
                """);
        Files.writeString(
                project.resolve("src/test/openapi/client.yaml"),
                generatedTestSource("v1"));
        writeManifest(project, repository);
    }

    private static String generatedTestSource(String revision) {
        return """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class GeneratedOpenApiTest {
                    @Test
                    fun runsGeneratedTest() {
                        assertEquals("main-%s", Main.message() + "-" + revision())
                    }

                    private fun revision(): String = "%s"
                }
                """.formatted(revision, revision);
    }

    private static void writeManifest(Path project, URI repository) throws IOException {
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "openapi-generated-kotlin-test"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "%s"

                [generated.test.client]
                kind = "openapi"
                language = "kotlin"
                input = "src/test/openapi/client.yaml"
                output = "target/generated/test-sources/openapi/client"
                generator = "kotlin"
                additionalProperties = { relativePath = "com/example/GeneratedOpenApiTest.kt", logFile = "openapi-test-invocations.txt" }

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies.test]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                "org.junit.platform:junit-platform-console-standalone" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                OpenApiKotlinCliFixture.VERSION,
                repository,
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
    }
}
