package sh.zolt.cli.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Canonical CLI/worker proof for Kotlin test diagnostic warning levels. */
final class KotlinTestWarningLevelIntegrationTest {
    private static final String DIAGNOSTIC = "REDUNDANT_VISIBILITY_MODIFIER";

    @TempDir
    private Path tempDir;

    @Test
    void overridesOneTestWarningAndInvalidatesWarmCompilation() throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeProject(project, repository, List.of("-Wextra"));

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();
            repository.close();

            CommandResult baseline = test(project, cache);
            assertSuccessful(baseline);
            assertTiming(baseline, "\"testCompilationMode\":\"full\"");

            CommandResult baselineWarm = test(project, cache);
            assertSuccessful(baselineWarm);
            assertTiming(baselineWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, List.of(
                    "-Wextra",
                    "-Xwarning-level=" + DIAGNOSTIC + ":error"));
            CommandResult promoted = test(project, cache);
            assertEquals(1, promoted.exitCode(), combined(promoted));
            assertTrue(diagnostics(promoted).contains("redundant visibility modifier"), combined(promoted));

            writeManifest(project, repository, List.of(
                    "-Wextra",
                    "-Werror",
                    "-Xwarning-level=" + DIAGNOSTIC + ":disabled"));
            CommandResult disabled = test(project, cache);
            assertSuccessful(disabled);
            assertTiming(disabled, "\"testCompilationMode\":\"full\"");

            CommandResult disabledWarm = test(project, cache);
            assertSuccessful(disabledWarm);
            assertTiming(disabledWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, List.of("-Wextra"));
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
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

    private static String diagnostics(CommandResult result) {
        return combined(result).toLowerCase(Locale.ROOT);
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }

    private static void writeProject(
            Path project,
            CliTestRepository repository,
            List<String> arguments) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/WarningLevelTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                public class WarningLevelTest {
                    @Test
                    public fun runsWithGranularWarningPolicy() {
                        assertEquals("granular", "granular")
                    }
                }
                """);
        writeManifest(project, repository, arguments);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            List<String> arguments) throws IOException {
        String compilerArguments = arguments.stream()
                .map(argument -> "\"" + argument + "\"")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-warning-level"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [compiler.test]
                args = [%s]

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
                compilerArguments,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
    }
}
