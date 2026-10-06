package sh.zolt.cli.build.kotlin.generation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;
import sh.zolt.cli.build.kotlin.OpenApiKotlinCliFixture;

/** CLI/worker proof for OpenAPI-owned Java consumed by authored Kotlin tests. */
final class OpenApiGeneratedJavaKotlinTestIntegrationTest {
    private static final String INPUT = "src/test/openapi/client.yaml";
    private static final String GENERATED_SOURCE =
            "target/generated/test-sources/openapi/client/com/example/GeneratedSupport.java";

    @TempDir
    private Path tempDir;

    @Test
    void generatesRunsRepairsAndInvalidatesJavaForKotlinTestsOffline() throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            OpenApiKotlinCliFixture.publish(repository, tempDir.resolve("fixture-work"));
            writeProject(project, repository);
            writeGeneratedSupport(project, "v1");

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();
            CommandResult offlineResolve = execute(
                    "resolve",
                    "--locked",
                    "--offline",
                    "--cwd", project.toString(),
                    "--cache-root", offlineCache.toString(),
                    "--no-progress");
            assertEquals(0, offlineResolve.exitCode(), offlineResolve.stderr());

            CommandResult first = test(project, offlineCache);
            assertSuccessful(first);
            Path generatedSource = project.resolve(GENERATED_SOURCE);
            Path generatedClass = project.resolve(
                    "target/test-classes/com/example/GeneratedSupport.class");
            assertTrue(Files.isRegularFile(generatedSource));
            assertTrue(Files.isRegularFile(generatedClass));
            byte[] initialSource = Files.readAllBytes(generatedSource);
            byte[] initialClass = Files.readAllBytes(generatedClass);
            assertEquals(List.of("run"), invocations(project));
            assertTiming(first, "full");

            CommandResult warm = test(project, offlineCache);
            assertSuccessful(warm);
            assertEquals(List.of("run"), invocations(project));
            assertTiming(warm, "skipped");

            Files.writeString(generatedSource, "not Java\n");
            CommandResult repaired = test(project, offlineCache);
            assertSuccessful(repaired);
            assertArrayEquals(initialSource, Files.readAllBytes(generatedSource));
            assertArrayEquals(initialClass, Files.readAllBytes(generatedClass));
            assertEquals(List.of("run", "run"), invocations(project));
            assertTiming(repaired, "skipped");

            writeGeneratedSupport(project, "v2");
            CommandResult changed = test(project, offlineCache);
            assertSuccessful(changed);
            assertFalse(Arrays.equals(initialSource, Files.readAllBytes(generatedSource)));
            assertFalse(Arrays.equals(initialClass, Files.readAllBytes(generatedClass)));
            assertEquals(List.of("run", "run", "run"), invocations(project));
            assertTiming(changed, "full");
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static CommandResult test(Path project, Path cache) {
        return execute(
                "test",
                "--no-build-cache",
                "--no-progress",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
    }

    private static void writeProject(Path project, CliTestRepository repository) throws IOException {
        Files.createDirectories(project.resolve("src/test/kotlin/com/example"));
        Files.createDirectories(project.resolve(INPUT).getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "openapi-java-kotlin-test"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [test.sources]
                kotlin = ["src/test/kotlin"]

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "%s"

                [generated.test.client]
                kind = "openapi"
                input = "%s"
                output = "target/generated/test-sources/openapi/client"
                generator = "java"
                additionalProperties = { relativePath = "com/example/GeneratedSupport.java", logFile = "openapi-test-invocations.txt" }

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies.test]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                "org.junit.platform:junit-platform-console-standalone" = "%s"
                """.formatted(
                currentJavaMajorVersion(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                OpenApiKotlinCliFixture.VERSION,
                INPUT,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
        Files.writeString(project.resolve(
                "src/test/kotlin/com/example/OpenApiJavaConsumerTest.kt"), """
                package com.example

                import org.junit.jupiter.api.Assertions.assertTrue
                import org.junit.jupiter.api.Test

                class OpenApiJavaConsumerTest {
                    @Test
                    fun consumesGeneratedJava() {
                        assertTrue(GeneratedSupport.value().startsWith("v"))
                    }
                }
                """);
    }

    private static void writeGeneratedSupport(Path project, String revision) throws IOException {
        Files.writeString(project.resolve(INPUT), """
                package com.example;

                public final class GeneratedSupport {
                    private GeneratedSupport() {}

                    public static String value() {
                        return "%s";
                    }
                }
                """.formatted(revision));
    }

    private static List<String> invocations(Path project) throws IOException {
        return Files.readAllLines(project.resolve("openapi-test-invocations.txt"));
    }

    private static void assertSuccessful(CommandResult result) {
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().matches("(?s).*\\b1 tests successful\\b.*"), result.stdout());
    }

    private static void assertTiming(CommandResult result, String mode) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile test sources\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing test compilation timing in:\n" + result.stderr()));
        assertTrue(line.contains("\"testCompilationMode\":\"" + mode + "\""), line);
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
