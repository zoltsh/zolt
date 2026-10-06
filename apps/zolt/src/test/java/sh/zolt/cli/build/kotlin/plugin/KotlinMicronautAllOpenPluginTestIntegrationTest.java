package sh.zolt.cli.build.kotlin.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Real CLI/worker proof that the Micronaut all-open preset reaches Kotlin test compilation. */
final class KotlinMicronautAllOpenPluginTestIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void opensATestLocalMicronautTypeOffline() throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path artifactCache = tempDir.resolve("artifact-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            KotlinCompilerCliFixture.publishAllOpen(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeProject(project, repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), combined(resolve));
            Files.move(onlineCache, artifactCache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult result = execute(
                    "test",
                    "--no-build-cache",
                    "--timings",
                    "--timings-format", "json",
                    "--cwd", project.toString(),
                    "--cache-root", artifactCache.toString(),
                    "--no-progress");

            assertEquals(0, result.exitCode(), combined(result));
            assertTrue(result.stdout().contains("Tests passed"), result.stdout());
            assertTrue(result.stdout().matches("(?s).*\\b1 tests successful\\b.*"), result.stdout());
            assertTiming(result, "full");
            assertTrue(Files.isRegularFile(project.resolve(
                    "target/test-classes/com/example/TestService.class")));
            assertFalse(Files.exists(project.resolve(
                    "target/classes/com/example/TestService.class")));
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static void writeProject(
            Path project,
            CliTestRepository repository) throws IOException {
        Path around = project.resolve("src/test/kotlin/io/micronaut/aop/Around.kt");
        Path test = project.resolve("src/test/kotlin/com/example/MicronautAllOpenTest.kt");
        Files.createDirectories(around.getParent());
        Files.createDirectories(test.getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-micronaut-all-open-test"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"
                plugins = ["micronaut"]

                [test.sources]
                kotlin = ["src/test/kotlin"]

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
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
        Files.writeString(around, """
                package io.micronaut.aop

                @Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
                @Retention(AnnotationRetention.RUNTIME)
                annotation class Around
                """);
        Files.writeString(test, """
                package com.example

                import io.micronaut.aop.Around
                import java.lang.reflect.Modifier
                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Assertions.assertFalse
                import org.junit.jupiter.api.Test

                @Around
                annotation class Traced

                @Traced
                class TestService {
                    fun message(): String = "test"
                }

                class MicronautAllOpenTest {
                    @Test
                    fun opensMicronautTypeAndMethod() {
                        val type = TestService::class.java
                        assertFalse(Modifier.isFinal(type.modifiers))
                        assertFalse(Modifier.isFinal(type.getDeclaredMethod("message").modifiers))
                        assertEquals("test", TestService().message())
                    }
                }
                """);
    }

    private static void assertTiming(CommandResult result, String mode) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile test sources\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing compile test sources timing in:\n" + result.stderr()));
        assertTrue(line.contains("\"testCompilationMode\":\"" + mode + "\""), line);
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }
}
