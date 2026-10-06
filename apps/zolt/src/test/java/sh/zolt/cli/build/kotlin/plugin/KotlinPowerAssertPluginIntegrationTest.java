package sh.zolt.cli.build.kotlin.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** Real CLI proof that the locked Power-assert plugin enriches Kotlin test failures. */
final class KotlinPowerAssertPluginIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void reportsIntermediateAssertionValuesOffline() throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path artifactCache = tempDir.resolve("artifact-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            KotlinCompilerCliFixture.publishPowerAssert(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeProject(project, repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), combined(resolve));
            assertTrue(Files.readString(project.resolve("zolt.lock")).contains(
                    "id = \"org.jetbrains.kotlin:kotlin-power-assert-compiler-plugin-embeddable\""));
            Files.move(onlineCache, artifactCache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult result = execute(
                    "test",
                    "--no-build-cache",
                    "--jvm-arg=-ea",
                    "--cwd", project.toString(),
                    "--cache-root", artifactCache.toString(),
                    "--no-progress");

            String output = combined(result);
            assertEquals(1, result.exitCode(), output);
            assertTrue(output.contains("Incorrect length"), output);
            assertTrue(output.contains("greeting.length == target.substring(1, 4).length"), output);
            assertTrue(output.contains("      orl"), output);
            assertTrue(output.contains("5"), output);
            assertTrue(output.contains("3"), output);
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static void writeProject(
            Path project,
            CliTestRepository repository) throws IOException {
        Path test = project.resolve("src/test/kotlin/com/example/PowerAssertTest.kt");
        Files.createDirectories(test.getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-power-assert"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"
                plugins = ["power-assert"]

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
        Files.writeString(test, """
                package com.example

                import org.junit.jupiter.api.Test

                class PowerAssertTest {
                    @Test
                    fun explainsIntermediateValues() {
                        val greeting = "Hello"
                        val target = "world!"
                        assert(greeting.length == target.substring(1, 4).length) {
                            "Incorrect length"
                        }
                    }
                }
                """);
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }
}
