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

/** Canonical CLI/worker proof for Kotlin test language and API version pins. */
final class KotlinTestVersionArgumentsIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void enforcesIndependentTestVersionPinsAndInvalidatesWarmCompilation() throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeProject(project, repository, "1.8", "1.8");

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();
            repository.close();

            CommandResult languageFailure = test(project, cache);
            assertEquals(1, languageFailure.exitCode(), combined(languageFailure));
            assertTrue(combined(languageFailure).contains("language version 1.9"), combined(languageFailure));

            writeManifest(project, repository, "1.9", "1.8");
            CommandResult apiFailure = test(project, cache);
            assertEquals(1, apiFailure.exitCode(), combined(apiFailure));
            assertTrue(combined(apiFailure).contains("ExperimentalStdlibApi"), combined(apiFailure));

            writeManifest(project, repository, "1.9", "1.8", true);
            CommandResult optedIn = test(project, cache);
            assertSuccessful(optedIn);
            assertTiming(optedIn, "\"testCompilationMode\":\"full\"");
            assertTrue(Files.isRegularFile(project.resolve(
                    "target/test-classes/com/example/VersionedTest.class")));

            CommandResult optedInWarm = test(project, cache);
            assertSuccessful(optedInWarm);
            assertTiming(optedInWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, "1.9", "1.9");
            CommandResult first = test(project, cache);
            assertSuccessful(first);
            assertTiming(first, "\"testCompilationMode\":\"full\"");
            assertTrue(Files.isRegularFile(project.resolve(
                    "target/test-classes/com/example/VersionedTest.class")));

            CommandResult warm = test(project, cache);
            assertSuccessful(warm);
            assertTiming(warm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, "1.9", "1.8");
            CommandResult downgraded = test(project, cache);
            assertEquals(1, downgraded.exitCode(), combined(downgraded));
            assertTrue(combined(downgraded).contains("ExperimentalStdlibApi"), combined(downgraded));

            writeManifest(project, repository, "1.9", "1.9");
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

    private static void writeProject(
            Path project,
            CliTestRepository repository,
            String languageVersion,
            String apiVersion) throws IOException {
        Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.createDirectories(project.resolve("src/test/kotlin/com/example"));
        Files.writeString(project.resolve("src/main/java/com/example/Main.java"), """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "main";
                    }
                }
                """);
        Files.writeString(project.resolve("src/test/kotlin/com/example/VersionedTest.kt"), """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                data object Marker

                enum class Color { RED }

                class VersionedTest {
                    @Test
                    fun usesPinnedLanguageAndApi() {
                        assertEquals("main-Marker-1", Main.message() + "-" + Marker + "-" + Color.entries.size)
                    }
                }
                """);
        writeManifest(project, repository, languageVersion, apiVersion);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            String languageVersion,
            String apiVersion) throws IOException {
        writeManifest(project, repository, languageVersion, apiVersion, false);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            String languageVersion,
            String apiVersion,
            boolean optIn) throws IOException {
        String optInArgument = optIn
                ? ", \"-opt-in=kotlin.ExperimentalStdlibApi\""
                : "";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-version-arguments"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [compiler.test]
                args = ["-language-version", "%s", "-api-version", "%s"%s]

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
                apiVersion,
                optInArgument,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
    }
}
