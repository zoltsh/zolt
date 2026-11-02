package sh.zolt.cli.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Canonical CLI/worker proof for Kotlin test assertion modes. */
final class KotlinTestAssertionModeIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void changesTestAssertionBehaviorAndInvalidatesWarmCompilation() throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeSource(project);
            writeManifest(project, repository, "always-enable");

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            Path stdlib = stdlib(cache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult alwaysEnabled = test(project, cache);
            assertSuccessful(alwaysEnabled);
            assertTiming(alwaysEnabled, "\"testCompilationMode\":\"full\"");
            assertEquals("threw:1", behavior(project, stdlib, false));

            CommandResult alwaysEnabledWarm = test(project, cache);
            assertSuccessful(alwaysEnabledWarm);
            assertTiming(alwaysEnabledWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, "always-disable");
            CommandResult alwaysDisabled = test(project, cache);
            assertSuccessful(alwaysDisabled);
            assertTiming(alwaysDisabled, "\"testCompilationMode\":\"full\"");
            assertEquals("passed:0", behavior(project, stdlib, false));
            assertEquals("passed:0", behavior(project, stdlib, true));

            writeManifest(project, repository, "jvm");
            CommandResult jvm = test(project, cache);
            assertSuccessful(jvm);
            assertTiming(jvm, "\"testCompilationMode\":\"full\"");
            assertEquals("passed:0", behavior(project, stdlib, false));
            assertEquals("threw:1", behavior(project, stdlib, true));

            writeManifest(project, repository, "legacy");
            CommandResult legacy = test(project, cache);
            assertSuccessful(legacy);
            assertTiming(legacy, "\"testCompilationMode\":\"full\"");
            assertEquals("passed:1", behavior(project, stdlib, false));
            assertEquals("threw:1", behavior(project, stdlib, true));

            CommandResult legacyWarm = test(project, cache);
            assertSuccessful(legacyWarm);
            assertTiming(legacyWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, "always-enable");
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            assertEquals("threw:1", behavior(project, stdlib, false));
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

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }

    private static String behavior(
            Path project,
            Path stdlib,
            boolean assertionsEnabled) throws Exception {
        URL output = project.resolve("target/test-classes").toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {output, stdlib.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            loader.setDefaultAssertionStatus(assertionsEnabled);
            return (String) Class.forName("com.example.TestAssertionApi", true, loader)
                    .getMethod("behavior")
                    .invoke(null);
        }
    }

    private static Path stdlib(Path cache) throws IOException {
        String fileName = "kotlin-stdlib-" + KotlinCompilerCliFixture.KOTLIN_VERSION + ".jar";
        try (Stream<Path> paths = Files.walk(cache)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Kotlin stdlib was not cached under " + cache));
        }
    }

    private static void writeSource(Path project) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/AssertionModeTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                object TestAssertionApi {
                    private var evaluations = 0

                    @JvmStatic
                    fun behavior(): String {
                        evaluations = 0
                        return try {
                            assert(recordFalse()) { "failed" }
                            "passed:$evaluations"
                        } catch (_: AssertionError) {
                            "threw:$evaluations"
                        }
                    }

                    private fun recordFalse(): Boolean {
                        evaluations++
                        return false
                    }
                }

                class AssertionModeTest {
                    @Test
                    fun remainsRunnable() {
                        assertEquals("Zolt", "Zolt")
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            String assertionMode) throws IOException {
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-assertion-mode"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [compiler.test]
                args = ["-Xassertions=%s"]

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
                assertionMode,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
    }
}
