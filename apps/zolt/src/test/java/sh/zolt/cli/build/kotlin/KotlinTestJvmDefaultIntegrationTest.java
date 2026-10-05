package sh.zolt.cli.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Canonical CLI/worker proof for Kotlin test JVM-default modes. */
final class KotlinTestJvmDefaultIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void changesTestInterfaceBytecodeAndCleansStaleCompatibilityOutput() throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeProject(project, repository, "disable");

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();
            repository.close();

            CommandResult disabled = test(project, cache);
            assertSuccessful(disabled);
            assertTiming(disabled, "\"testCompilationMode\":\"full\"");
            assertFalse(isDefaultMethod(project));
            assertTrue(Files.isRegularFile(defaultImpls(project)));

            CommandResult warm = test(project, cache);
            assertSuccessful(warm);
            assertTiming(warm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, "no-compatibility");
            CommandResult modern = test(project, cache);
            assertSuccessful(modern);
            assertTiming(modern, "\"testCompilationMode\":\"full\"");
            assertTrue(isDefaultMethod(project));
            assertFalse(Files.exists(defaultImpls(project)));

            writeManifest(project, repository, "enable");
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            assertTrue(isDefaultMethod(project));
            assertTrue(Files.isRegularFile(defaultImpls(project)));
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
        assertEquals(0, result.exitCode(), combined(result));
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

    private static boolean isDefaultMethod(Path project) throws Exception {
        URL output = project.resolve("target/test-classes").toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {output},
                ClassLoader.getPlatformClassLoader())) {
            return Class.forName("com.example.Greeting", true, loader)
                    .getDeclaredMethod("message")
                    .isDefault();
        }
    }

    private static Path defaultImpls(Path project) {
        return project.resolve("target/test-classes/com/example/Greeting$DefaultImpls.class");
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }

    private static void writeProject(
            Path project,
            CliTestRepository repository,
            String mode) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/JvmDefaultTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                interface Greeting {
                    fun message(): String = "hello"
                }

                class JvmDefaultTest {
                    @Test
                    fun usesInterfaceDefault() {
                        assertEquals("hello", object : Greeting {}.message())
                    }
                }
                """);
        writeManifest(project, repository, mode);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            String mode) throws IOException {
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-jvm-default"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [compiler.test]
                args = ["-jvm-default=%s"]

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
                mode,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
    }
}
