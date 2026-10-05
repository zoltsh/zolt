package sh.zolt.cli.build.kotlin.compatibility;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

/** Canonical CLI/worker proof for consuming intentionally unstable Kotlin ABI. */
final class KotlinTestAbiStabilityIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void requiresExplicitUnstableDependencyOptInOfflineAndInvalidatesWarmCompilation()
            throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeSources(project);
            writeManifest(project, repository, false);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();
            repository.close();

            CommandResult rejected = test(project, cache);
            assertEquals(1, rejected.exitCode(), combined(rejected));
            assertTrue(diagnostics(rejected).contains(
                    "classes compiled by an unstable version"), combined(rejected));

            writeManifest(project, repository, true);
            CommandResult allowed = test(project, cache);
            assertSuccessful(allowed);
            assertTiming(allowed, "\"testCompilationMode\":\"full\"");
            byte[] allowedBytes = Files.readAllBytes(testClass(project));

            CommandResult allowedWarm = test(project, cache);
            assertSuccessful(allowedWarm);
            assertTiming(allowedWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, false);
            CommandResult rejectedAgain = test(project, cache);
            assertEquals(1, rejectedAgain.exitCode(), combined(rejectedAgain));
            assertTrue(diagnostics(rejectedAgain).contains(
                    "classes compiled by an unstable version"), combined(rejectedAgain));

            writeManifest(project, repository, true);
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            assertArrayEquals(allowedBytes, Files.readAllBytes(testClass(project)));

            CommandResult restoredWarm = test(project, cache);
            assertSuccessful(restoredWarm);
            assertTiming(restoredWarm, "\"testCompilationMode\":\"skipped\"");
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

    private static Path testClass(Path project) {
        return project.resolve("target/test-classes/com/example/AbiStabilityTest.class");
    }

    private static void writeSources(Path project) throws IOException {
        Path main = project.resolve("src/main/kotlin/com/example/UnstableLibrary.kt");
        Files.createDirectories(main.getParent());
        Files.writeString(main, """
                package com.example

                object UnstableLibrary {
                    @JvmStatic
                    fun answer(): Int = 42
                }
                """);

        Path test = project.resolve("src/test/kotlin/com/example/AbiStabilityTest.kt");
        Files.createDirectories(test.getParent());
        Files.writeString(test, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class AbiStabilityTest {
                    @Test
                    fun consumesUnstableLibrary() {
                        assertEquals(42, UnstableLibrary.answer())
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean allowUnstableDependencies) throws IOException {
        String testArguments = allowUnstableDependencies
                ? "\"-parameters\", \"-Xallow-unstable-dependencies\""
                : "\"-parameters\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-abi-stability"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "%s"

                [compiler]
                args = ["-parameters", "-Xabi-stability=unstable"]

                [compiler.test]
                args = [%s]

                [test.sources]
                kotlin = ["src/test/kotlin"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"

                [dependencies.test]
                "org.junit.platform:junit-platform-console-standalone" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                testArguments,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
    }
}
