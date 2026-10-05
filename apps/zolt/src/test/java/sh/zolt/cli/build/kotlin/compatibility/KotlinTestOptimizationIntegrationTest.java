package sh.zolt.cli.build.kotlin.compatibility;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
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

/** Canonical CLI/worker proof for Kotlin backend-optimization control. */
final class KotlinTestOptimizationIntegrationTest {
    private static final int[] COMPACT_BRANCH = {0x1a, 0x04, 0x60, 0x3c, 0x1b, 0x9e};
    private static final int[] EXPLICIT_ZERO_BRANCH = {0x1a, 0x04, 0x60, 0x3c, 0x1b, 0x03, 0xa4};

    @TempDir
    private Path tempDir;

    @Test
    void disablesTestOptimizationOfflineAndInvalidatesWarmCompilation()
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

            CommandResult optimized = test(project, cache);
            assertSuccessful(optimized);
            assertTiming(optimized, "\"testCompilationMode\":\"full\"");
            byte[] optimizedBytes = classFileBytes(project);
            assertTrue(contains(optimizedBytes, COMPACT_BRANCH));
            assertFalse(contains(optimizedBytes, EXPLICIT_ZERO_BRANCH));

            CommandResult optimizedWarm = test(project, cache);
            assertSuccessful(optimizedWarm);
            assertTiming(optimizedWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, true);
            CommandResult unoptimized = test(project, cache);
            assertSuccessful(unoptimized);
            assertTiming(unoptimized, "\"testCompilationMode\":\"full\"");
            byte[] unoptimizedBytes = classFileBytes(project);
            assertFalse(Arrays.equals(optimizedBytes, unoptimizedBytes));
            assertTrue(contains(unoptimizedBytes, EXPLICIT_ZERO_BRANCH));
            assertFalse(contains(unoptimizedBytes, COMPACT_BRANCH));

            CommandResult unoptimizedWarm = test(project, cache);
            assertSuccessful(unoptimizedWarm);
            assertTiming(unoptimizedWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, false);
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            assertArrayEquals(optimizedBytes, classFileBytes(project));
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

    private static byte[] classFileBytes(Path project) throws IOException {
        return Files.readAllBytes(project.resolve(
                "target/test-classes/com/example/OptimizeTestApi.class"));
    }

    private static boolean contains(byte[] bytes, int[] sequence) {
        for (int start = 0; start <= bytes.length - sequence.length; start++) {
            boolean matches = true;
            for (int offset = 0; offset < sequence.length; offset++) {
                if ((bytes[start + offset] & 0xff) != sequence[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private static void writeSource(Path project) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/OptimizationTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                object OptimizeTestApi {
                    @JvmStatic
                    fun classify(value: Int): String {
                        val adjusted = value + 1
                        return if (adjusted > 0) "positive" else "non-positive"
                    }
                }

                class OptimizationTest {
                    @Test
                    fun preservesRuntimeBehavior() {
                        assertEquals("non-positive", OptimizeTestApi.classify(-2))
                        assertEquals("positive", OptimizeTestApi.classify(0))
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean noOptimize) throws IOException {
        String compilerArguments = noOptimize
                ? "\"-parameters\", \"-Xno-optimize\""
                : "\"-parameters\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-optimization"
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
