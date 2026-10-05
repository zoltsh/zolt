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

/** Canonical CLI/worker proof for rendering Kotlin test diagnostic names. */
final class KotlinTestRenderInternalDiagnosticNamesIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void rendersTestWarningAndErrorNamesOfflineWithoutChangingSuccessfulOutput()
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
            CommandResult named = test(project, cache);
            assertSuccessful(named);
            assertTiming(named, "\"testCompilationMode\":\"full\"");
            OutputBytes namedBytes = outputBytes(project);
            assertArrayEquals(baselineBytes.test(), namedBytes.test());
            assertArrayEquals(baselineBytes.file(), namedBytes.file());

            CommandResult namedWarm = test(project, cache);
            assertSuccessful(namedWarm);
            assertTiming(namedWarm, "\"testCompilationMode\":\"skipped\"");

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
            assertTrue(ordinaryDiagnostics.contains("is deprecated. old api"));
            assertFalse(ordinaryDiagnostics.contains("[return_type_mismatch]"));
            assertFalse(ordinaryDiagnostics.contains("[deprecation]"));

            writeManifest(project, repository, true);
            CommandResult namedFailure = test(project, cache);
            assertEquals(1, namedFailure.exitCode(), combined(namedFailure));
            String namedFailureDiagnostics = diagnostics(combined(namedFailure));
            assertTrue(namedFailureDiagnostics.contains("[return_type_mismatch]"));
            assertTrue(namedFailureDiagnostics.contains("return type mismatch"));
            assertTrue(namedFailureDiagnostics.contains("[deprecation]"));
            assertTrue(namedFailureDiagnostics.contains("is deprecated. old api"));
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
                Files.readAllBytes(output.resolve("DiagnosticNamesTest.class")),
                Files.readAllBytes(output.resolve("DiagnosticNamesTestKt.class")));
    }

    private static void writeSource(Path project, boolean broken) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/DiagnosticNamesTest.kt");
        Files.createDirectories(source.getParent());
        String brokenDeclaration = broken ? "fun broken(): Int = \"no\"" : "";
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                @Deprecated("old API")
                fun oldApi() {}

                fun warningSite() {
                    oldApi()
                }

                class DiagnosticNamesTest {
                    @Test
                    fun preservesRuntimeBehavior() {
                        assertEquals("named", "named")
                    }
                }

                %s
                """.formatted(brokenDeclaration));
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean renderDiagnosticNames) throws IOException {
        String compilerArguments = renderDiagnosticNames
                ? "\"-parameters\", \"-Xreport-all-warnings\","
                        + " \"-Xrender-internal-diagnostic-names\""
                : "\"-parameters\", \"-Xreport-all-warnings\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-diagnostic-names"
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
