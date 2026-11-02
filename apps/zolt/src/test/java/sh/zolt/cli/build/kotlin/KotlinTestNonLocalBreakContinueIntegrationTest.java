package sh.zolt.cli.build.kotlin;

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

/** Canonical CLI/worker proof for non-local loop control across language modes. */
final class KotlinTestNonLocalBreakContinueIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void enablesTheTestPreviewOfflineAndInvalidatesWarmCompilation() throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeSource(project);
            writeManifest(project, repository, "2.1", false);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();
            repository.close();

            CommandResult unavailable = test(project, cache);
            assertEquals(1, unavailable.exitCode(), combined(unavailable));
            assertTrue(
                    combined(unavailable).contains(
                            "break continue in inline lambdas\" is only available since language version 2.2"),
                    combined(unavailable));

            writeManifest(project, repository, "2.1", true);
            CommandResult preview = test(project, cache);
            assertSuccessful(preview);
            assertTiming(preview, "\"testCompilationMode\":\"full\"");

            CommandResult previewWarm = test(project, cache);
            assertSuccessful(previewWarm);
            assertTiming(previewWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, "2.2", false);
            CommandResult stable = test(project, cache);
            assertSuccessful(stable);
            assertTiming(stable, "\"testCompilationMode\":\"full\"");

            CommandResult stableWarm = test(project, cache);
            assertSuccessful(stableWarm);
            assertTiming(stableWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, "2.1", true);
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

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }

    private static void writeSource(Path project) throws IOException {
        Path source = project.resolve(
                "src/test/kotlin/com/example/NonLocalLoopControlTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                private fun collect(values: List<Int?>): String {
                    val accepted = mutableListOf<Int>()
                    for (element in values) {
                        val value = element ?: run {
                            continue
                        }
                        if (value == 0) {
                            run {
                                break
                            }
                        }
                        accepted += value
                    }
                    return accepted.joinToString(",")
                }

                class NonLocalLoopControlTest {
                    @Test
                    fun skipsNullsAndStopsAtZero() {
                        assertEquals("-2,3", collect(listOf(null, -2, 3, null, 0, 9)))
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            String languageVersion,
            boolean enablePreview) throws IOException {
        String previewArgument = enablePreview
                ? ", \"-Xnon-local-break-continue\""
                : "";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-non-local-loop-control"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [compiler.test]
                args = ["-language-version", "%s"%s]

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
                languageVersion,
                previewArgument,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
    }
}
