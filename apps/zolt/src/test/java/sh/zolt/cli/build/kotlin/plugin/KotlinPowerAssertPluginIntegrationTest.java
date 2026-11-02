package sh.zolt.cli.build.kotlin.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCliBuildCacheTestSupport;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Real CLI proof that the locked Power-assert plugin enriches Kotlin test failures. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class KotlinPowerAssertPluginIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void reportsIntermediateAssertionValuesOffline() throws Exception {
        assumeTrue(System.getenv("ZOLT_USER_HOME") == null, "test needs an isolated user.home fallback");
        String previousUserHome = System.getProperty("user.home");
        Path fakeUserHome = tempDir.resolve("fake-user-home");
        System.setProperty("user.home", fakeUserHome.toString());
        try {
            verifyCacheLifecycle(fakeUserHome);
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private void verifyCacheLifecycle(Path fakeUserHome) throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path artifactCache = tempDir.resolve("artifact-cache");
        KotlinCliBuildCacheTestSupport.configure(fakeUserHome);
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            KotlinCompilerCliFixture.publishPowerAssert(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeProject(project, repository);
            resolve(project, onlineCache);
            assertTrue(Files.readString(project.resolve("zolt.lock")).contains(
                    "id = \"org.jetbrains.kotlin:kotlin-power-assert-compiler-plugin-embeddable\""));
            Files.move(onlineCache, artifactCache);
            repository.clearAuthorizations();
            repository.close();

            assertPowerAssertFailure(runTests(project, artifactCache), "full");
            assertPowerAssertFailure(runTests(project, artifactCache), "skipped");
            KotlinCliBuildCacheTestSupport.deleteTrees(project.resolve("target"));
            assertPowerAssertFailure(runTests(project, artifactCache), "restored");
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static void resolve(Path project, Path cacheRoot) {
        CommandResult resolve = execute(
                "resolve",
                "--cwd", project.toString(),
                "--cache-root", cacheRoot.toString(),
                "--no-progress");
        assertEquals(0, resolve.exitCode(), combined(resolve));
    }

    private static CommandResult runTests(Path project, Path artifactCache) {
        return execute(
                "test",
                "--jvm-arg=-ea",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString(),
                "--no-progress");
    }

    private static void assertPowerAssertFailure(CommandResult result, String mode) {
        String output = combined(result);
        assertEquals(1, result.exitCode(), output);
        assertTrue(output.contains("Incorrect length"), output);
        assertTrue(output.contains("greeting.length == target.substring(1, 4).length"), output);
        assertTrue(output.contains("      orl"), output);
        assertTrue(output.contains("5"), output);
        assertTrue(output.contains("3"), output);
        String timing = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile test sources\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing compile test sources timing in:\n" + result.stderr()));
        assertTrue(timing.contains("\"testCompilationMode\":\"" + mode + "\""), timing);
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
