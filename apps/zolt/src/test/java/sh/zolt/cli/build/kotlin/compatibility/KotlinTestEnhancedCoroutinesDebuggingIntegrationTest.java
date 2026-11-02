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

/** Canonical CLI/worker proof for enhanced Kotlin test coroutine debugging. */
final class KotlinTestEnhancedCoroutinesDebuggingIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void emitsTestCoroutineDebugMarkersOfflineAndInvalidatesWarmCompilation()
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
            assertFalse(contains(baselineBytes.file(), "$ecd$checkContinuation"));
            assertFalse(contains(baselineBytes.file(), "GeneratedCodeMarkers.kt"));

            CommandResult baselineWarm = test(project, cache);
            assertSuccessful(baselineWarm);
            assertTiming(baselineWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, true);
            CommandResult enhanced = test(project, cache);
            assertSuccessful(enhanced);
            assertTiming(enhanced, "\"testCompilationMode\":\"full\"");
            OutputBytes enhancedBytes = outputBytes(project);
            assertFalse(Arrays.equals(baselineBytes.file(), enhancedBytes.file()));
            assertArrayEquals(baselineBytes.continuation(), enhancedBytes.continuation());
            assertTrue(contains(enhancedBytes.file(), "$ecd$checkContinuation"));
            assertTrue(contains(enhancedBytes.file(), "$ecd$tableswitch"));
            assertTrue(contains(enhancedBytes.file(), "$ecd$checkResult"));
            assertTrue(contains(enhancedBytes.file(), "$ecd$checkCOROUTINE_SUSPENDED"));
            assertTrue(contains(enhancedBytes.file(), "$ecd$unreachable"));
            assertTrue(contains(enhancedBytes.file(), "GeneratedCodeMarkers.kt"));

            CommandResult enhancedWarm = test(project, cache);
            assertSuccessful(enhancedWarm);
            assertTiming(enhancedWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, false);
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            OutputBytes restoredBytes = outputBytes(project);
            assertArrayEquals(baselineBytes.file(), restoredBytes.file());
            assertArrayEquals(baselineBytes.continuation(), restoredBytes.continuation());
            assertFalse(contains(restoredBytes.file(), "$ecd$checkContinuation"));
            assertFalse(contains(restoredBytes.file(), "GeneratedCodeMarkers.kt"));
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
                Files.readAllBytes(output.resolve("CoroutineDebugTestKt.class")),
                Files.readAllBytes(output.resolve("CoroutineDebugTestKt$transformed$1.class")));
    }

    private static boolean contains(byte[] bytes, String value) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(value);
    }

    private static void writeSource(Path project) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/CoroutineDebugTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import kotlin.coroutines.Continuation
                import kotlin.coroutines.EmptyCoroutineContext
                import kotlin.coroutines.resume
                import kotlin.coroutines.startCoroutine
                import kotlin.coroutines.suspendCoroutine
                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                suspend fun marker(value: String): String = suspendCoroutine { continuation ->
                    continuation.resume(value)
                }

                suspend fun transformed(input: String): String {
                    val resumed = marker(input)
                    return resumed.uppercase()
                }

                object CoroutineDebugTestApi {
                    @JvmStatic
                    fun result(): String {
                        var outcome: Result<String>? = null
                        suspend { transformed("debug") }.startCoroutine(
                            object : Continuation<String> {
                                override val context = EmptyCoroutineContext

                                override fun resumeWith(result: Result<String>) {
                                    outcome = result
                                }
                            }
                        )
                        return checkNotNull(outcome).getOrThrow()
                    }
                }

                class CoroutineDebugTest {
                    @Test
                    fun preservesRuntimeBehavior() {
                        assertEquals("DEBUG", CoroutineDebugTestApi.result())
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean enhancedDebugging) throws IOException {
        String compilerArguments = enhancedDebugging
                ? "\"-parameters\", \"-Xenhanced-coroutines-debugging\""
                : "\"-parameters\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-enhanced-coroutines-debugging"
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

    private record OutputBytes(byte[] file, byte[] continuation) {}
}
