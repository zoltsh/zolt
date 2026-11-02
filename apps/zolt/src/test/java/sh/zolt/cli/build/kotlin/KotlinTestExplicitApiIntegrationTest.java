package sh.zolt.cli.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** Canonical CLI/worker proof for Kotlin test explicit-API mode. */
final class KotlinTestExplicitApiIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void rejectsImplicitTestApiAndAcceptsCorrectedStrictApi() throws Exception {
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

            CommandResult baselineWarm = test(project, cache);
            assertSuccessful(baselineWarm);
            assertTiming(baselineWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, true);
            CommandResult rejected = test(project, cache);
            assertEquals(1, rejected.exitCode(), combined(rejected));
            assertTrue(diagnostics(rejected).contains("explicit api mode"), combined(rejected));

            writeSource(project, true);
            CommandResult strict = test(project, cache);
            assertSuccessful(strict);
            assertTiming(strict, "\"testCompilationMode\":\"full\"");

            CommandResult strictWarm = test(project, cache);
            assertSuccessful(strictWarm);
            assertTiming(strictWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, false);
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

    private static void writeSource(Path project, boolean explicit) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/ExplicitApiTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, explicit
                ? """
                        package com.example

                        import org.junit.jupiter.api.Assertions.assertEquals
                        import org.junit.jupiter.api.Test

                        public class ExplicitApiTest {
                            @Test
                            public fun runs(): Unit {
                                assertEquals("strict", "strict")
                            }
                        }
                        """
                : """
                        package com.example

                        import org.junit.jupiter.api.Assertions.assertEquals
                        import org.junit.jupiter.api.Test

                        class ExplicitApiTest {
                            @Test
                            fun runs() {
                                assertEquals("strict", "strict")
                            }
                        }
                        """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean strict) throws IOException {
        String compilerArguments = strict
                ? "\"-parameters\", \"-Xexplicit-api=strict\""
                : "\"-parameters\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-explicit-api"
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
