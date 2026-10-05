package sh.zolt.cli.build.kotlin.compatibility;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Canonical CLI/worker proof for numbered Kotlin test inline-scope markers. */
final class KotlinTestInlineScopeNumbersIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void numbersTestInlineScopeMarkersOfflineAndInvalidatesWarmCompilation()
            throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeSource(project);
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
            assertTrue(contains(baselineBytes.api(), "$i$f$doubled"));
            assertTrue(contains(baselineBytes.api(), "value$iv"));
            assertFalse(contains(baselineBytes.api(), "$i$f$doubled\\"));
            assertFalse(contains(baselineBytes.api(), "value\\"));

            CommandResult baselineWarm = test(project, cache);
            assertSuccessful(baselineWarm);
            assertTiming(baselineWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, true);
            CommandResult numbered = test(project, cache);
            assertSuccessful(numbered);
            assertTiming(numbered, "\"testCompilationMode\":\"full\"");
            OutputBytes numberedBytes = outputBytes(project);
            assertFalse(Arrays.equals(baselineBytes.api(), numberedBytes.api()));
            assertArrayEquals(baselineBytes.file(), numberedBytes.file());
            assertTrue(contains(numberedBytes.api(), "$i$f$doubled\\"));
            assertTrue(contains(numberedBytes.api(), "value\\"));
            assertFalse(contains(numberedBytes.api(), "value$iv"));

            CommandResult numberedWarm = test(project, cache);
            assertSuccessful(numberedWarm);
            assertTiming(numberedWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, false);
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            OutputBytes restoredBytes = outputBytes(project);
            assertArrayEquals(baselineBytes.api(), restoredBytes.api());
            assertArrayEquals(baselineBytes.file(), restoredBytes.file());
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

    private static OutputBytes outputBytes(Path project) throws IOException {
        Path output = project.resolve("target/test-classes/com/example");
        return new OutputBytes(
                Files.readAllBytes(output.resolve("InlineScopesTestApi.class")),
                Files.readAllBytes(output.resolve("InlineScopesTestKt.class")));
    }

    private static boolean contains(byte[] bytes, String value) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(value);
    }

    private static void writeSource(Path project) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/InlineScopesTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                @Suppress("NOTHING_TO_INLINE")
                inline fun doubled(value: Int): Int = value * 2

                object InlineScopesTestApi {
                    @JvmStatic
                    fun answer(): Int = doubled(21)
                }

                class InlineScopesTest {
                    @Test
                    fun preservesRuntimeBehavior() {
                        assertEquals(42, InlineScopesTestApi.answer())
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean useInlineScopeNumbers) throws IOException {
        String compilerArguments = useInlineScopeNumbers
                ? "\"-parameters\", \"-Xuse-inline-scopes-numbers\""
                : "\"-parameters\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-inline-scope-numbers"
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

    private record OutputBytes(byte[] api, byte[] file) {}
}
