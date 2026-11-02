package sh.zolt.cli.build.kotlin.language;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

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

/** Canonical CLI/worker proof for the legacy Kotlin context-receiver migration mode. */
final class KotlinTestContextReceiversIntegrationTest {
    private static final String RECEIVERS = "-Xcontext-receivers";
    private static final String PARAMETERS = "-Xcontext-parameters";

    @TempDir
    private Path tempDir;

    @Test
    void compilesLegacyContextReceiversOnlyInTheirExclusiveTestMode() throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeSource(project, false);
            writeManifest(project, repository, List.of("-parameters"));

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), combined(resolve));
            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult baseline = test(project, offlineCache);
            assertSuccessful(baseline);
            assertTiming(baseline, "full");

            writeSource(project, true);
            CommandResult disabled = test(project, offlineCache);
            assertEquals(1, disabled.exitCode(), combined(disabled));
            assertTrue(diagnostics(disabled).contains("context-parameters"), combined(disabled));

            writeManifest(project, repository, List.of("-parameters", RECEIVERS));
            CommandResult enabled = test(project, offlineCache);
            assertSuccessful(enabled);
            assertTiming(enabled, "full");

            CommandResult warm = test(project, offlineCache);
            assertSuccessful(warm);
            assertTiming(warm, "skipped");

            writeManifest(project, repository, List.of("-parameters", RECEIVERS, PARAMETERS));
            CommandResult incompatible = test(project, offlineCache);
            assertEquals(1, incompatible.exitCode(), combined(incompatible));
            assertTrue(diagnostics(incompatible).contains("mutually exclusive"), combined(incompatible));
            assertTrue(combined(incompatible).contains(RECEIVERS), combined(incompatible));
            assertTrue(combined(incompatible).contains(PARAMETERS), combined(incompatible));

            writeManifest(project, repository, List.of("-parameters", RECEIVERS));
            CommandResult restored = test(project, offlineCache);
            assertSuccessful(restored);
            assertTiming(restored, "skipped");
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "context-receiver test commands must remain cache-only after resolve");
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

    private static void assertTiming(CommandResult result, String mode) {
        String timing = result.stderr().lines()
                .filter(line -> line.contains("\"phase\":\"compile test sources\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing test compilation timing in:\n" + result.stderr()));
        assertTrue(timing.contains("\"testCompilationMode\":\"" + mode + "\""), timing);
    }

    private static void writeSource(Path project, boolean contextReceiver) throws Exception {
        Path source = project.resolve("src/test/kotlin/com/example/ContextReceiversTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, contextReceiver
                ? """
                        package com.example

                        import org.junit.jupiter.api.Assertions.assertEquals
                        import org.junit.jupiter.api.Test

                        context(String)
                        fun contextualMessage(): String = "value=$length"

                        class ContextReceiversTest {
                            @Test
                            fun resolvesLegacyContextReceiver() {
                                assertEquals("value=7", with("context") { contextualMessage() })
                            }
                        }
                        """
                : """
                        package com.example

                        import org.junit.jupiter.api.Assertions.assertEquals
                        import org.junit.jupiter.api.Test

                        fun contextualMessage(value: String): String = "value=${value.length}"

                        class ContextReceiversTest {
                            @Test
                            fun resolvesExplicitParameter() {
                                assertEquals("value=7", contextualMessage("context"))
                            }
                        }
                        """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            List<String> arguments) throws Exception {
        String compilerArguments = arguments.stream()
                .map(argument -> "\"" + argument + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-context-receivers"
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

    private static String diagnostics(CommandResult result) {
        return combined(result).toLowerCase(Locale.ROOT);
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }
}
