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

/** Canonical CLI/worker proof for Kotlin test context parameters. */
final class KotlinTestContextParametersIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void compilesContextParametersOnlyWhenTestPreviewIsEnabled() throws Exception {
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

            writeSource(project, true);
            CommandResult disabled = test(project, cache);
            assertEquals(1, disabled.exitCode(), combined(disabled));
            assertTrue(diagnostics(disabled).contains("-xcontext-parameters"), combined(disabled));

            writeManifest(project, repository, true);
            CommandResult preview = test(project, cache);
            assertSuccessful(preview);
            assertTiming(preview, "\"testCompilationMode\":\"full\"");

            CommandResult previewWarm = test(project, cache);
            assertSuccessful(previewWarm);
            assertTiming(previewWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, false);
            CommandResult removed = test(project, cache);
            assertEquals(1, removed.exitCode(), combined(removed));
            assertTrue(diagnostics(removed).contains("-xcontext-parameters"), combined(removed));

            writeManifest(project, repository, true);
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

    private static String diagnostics(CommandResult result) {
        return combined(result).toLowerCase(Locale.ROOT);
    }

    private static void writeSource(Path project, boolean contextParameters) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/ContextParametersTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, contextParameters
                ? """
                        package com.example

                        import org.junit.jupiter.api.Assertions.assertEquals
                        import org.junit.jupiter.api.Test

                        interface UserService {
                            fun findUserById(id: Int): String
                        }

                        context(users: UserService)
                        fun firstUser(): String = users.findUserById(1)

                        class ContextParametersTest {
                            @Test
                            fun resolvesContextParameters() {
                                val users = object : UserService {
                                    override fun findUserById(id: Int): String = "context-$id"
                                }
                                assertEquals("context-1", context(users) { firstUser() })
                            }
                        }
                        """
                : """
                        package com.example

                        import org.junit.jupiter.api.Assertions.assertEquals
                        import org.junit.jupiter.api.Test

                        interface UserService {
                            fun findUserById(id: Int): String
                        }

                        fun firstUser(users: UserService): String = users.findUserById(1)

                        class ContextParametersTest {
                            @Test
                            fun resolvesContextParameters() {
                                val users = object : UserService {
                                    override fun findUserById(id: Int): String = "context-$id"
                                }
                                assertEquals("context-1", firstUser(users))
                            }
                        }
                        """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean preview) throws IOException {
        String compilerArguments = preview
                ? "\"-parameters\", \"-Xcontext-parameters\""
                : "\"-parameters\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-context-parameters"
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
