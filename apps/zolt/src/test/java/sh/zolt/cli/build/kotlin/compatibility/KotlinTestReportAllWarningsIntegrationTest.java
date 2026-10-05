package sh.zolt.cli.build.kotlin.compatibility;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Canonical CLI/worker proof for complete Kotlin test warning reports. */
final class KotlinTestReportAllWarningsIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void reportsTestWarningsAlongsideErrorsOfflineAndInvalidatesWarmCompilation()
            throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeSource(project, false);
            writeManifest(project, repository, false);

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
            OutputBytes baselineBytes = outputBytes(project);

            CommandResult baselineWarm = test(project, cache);
            assertSuccessful(baselineWarm);
            assertTiming(baselineWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, true);
            CommandResult enabled = test(project, cache);
            assertSuccessful(enabled);
            assertTiming(enabled, "\"testCompilationMode\":\"full\"");
            OutputBytes enabledBytes = outputBytes(project);
            assertArrayEquals(baselineBytes.test(), enabledBytes.test());
            assertArrayEquals(baselineBytes.file(), enabledBytes.file());

            CommandResult enabledWarm = test(project, cache);
            assertSuccessful(enabledWarm);
            assertTiming(enabledWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, false);
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            OutputBytes restoredBytes = outputBytes(project);
            assertArrayEquals(baselineBytes.test(), restoredBytes.test());
            assertArrayEquals(baselineBytes.file(), restoredBytes.file());

            writeSource(project, true);
            CommandResult ordinaryFailure = test(project, cache);
            assertEquals(1, ordinaryFailure.exitCode(), combined(ordinaryFailure));
            String ordinaryDiagnostics = diagnostics(combined(ordinaryFailure));
            assertTrue(ordinaryDiagnostics.contains("return type mismatch"));
            assertFalse(ordinaryDiagnostics.contains("is deprecated. old api"));

            writeManifest(project, repository, true);
            CommandResult completeFailure = test(project, cache);
            assertEquals(1, completeFailure.exitCode(), combined(completeFailure));
            String completeDiagnostics = diagnostics(combined(completeFailure));
            assertTrue(completeDiagnostics.contains("return type mismatch"));
            assertTrue(completeDiagnostics.contains("is deprecated. old api"));
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

    private static String diagnostics(String output) {
        return output.toLowerCase(Locale.ROOT);
    }

    private static OutputBytes outputBytes(Path project) throws IOException {
        Path output = project.resolve("target/test-classes/com/example");
        return new OutputBytes(
                Files.readAllBytes(output.resolve("WarningReportTest.class")),
                Files.readAllBytes(output.resolve("WarningReportTestKt.class")));
    }

    private static void writeSource(Path project, boolean broken) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/WarningReportTest.kt");
        Files.createDirectories(source.getParent());
        String brokenDeclaration = broken ? "fun broken(): String = 42" : "";
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                @Deprecated("old API")
                fun oldApi() {}

                fun warningSite() {
                    oldApi()
                }

                class WarningReportTest {
                    @Test
                    fun preservesRuntimeBehavior() {
                        assertEquals("complete", "complete")
                    }
                }

                %s
                """.formatted(brokenDeclaration));
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean reportAllWarnings) throws IOException {
        String compilerArguments = reportAllWarnings
                ? "\"-parameters\", \"-Xreport-all-warnings\""
                : "\"-parameters\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-complete-warning-reports"
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

    private record OutputBytes(byte[] test, byte[] file) {}
}
