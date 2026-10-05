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

/** Canonical CLI/worker proof for Kotlin method-inlining control. */
final class KotlinTestInliningIntegrationTest {
    private static final int[] INLINED_BODY = {
        0x10, 0x15, 0x3b, 0x03, 0x3c, 0x1a, 0x05, 0x68, 0xac
    };
    private static final int[] STATIC_CALL = {0x10, 0x15, 0xb8, -1, -1, 0xac};

    @TempDir
    private Path tempDir;

    @Test
    void disablesTestInliningOfflineAndInvalidatesWarmCompilation()
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
            byte[] baselineBytes = classFileBytes(project);
            assertTrue(contains(baselineBytes, INLINED_BODY));
            assertFalse(contains(baselineBytes, STATIC_CALL));

            CommandResult baselineWarm = test(project, cache);
            assertSuccessful(baselineWarm);
            assertTiming(baselineWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, true);
            CommandResult disabled = test(project, cache);
            assertSuccessful(disabled);
            assertTiming(disabled, "\"testCompilationMode\":\"full\"");
            byte[] disabledBytes = classFileBytes(project);
            assertFalse(Arrays.equals(baselineBytes, disabledBytes));
            assertFalse(contains(disabledBytes, INLINED_BODY));
            assertTrue(contains(disabledBytes, STATIC_CALL));

            CommandResult disabledWarm = test(project, cache);
            assertSuccessful(disabledWarm);
            assertTiming(disabledWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, false);
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            assertArrayEquals(baselineBytes, classFileBytes(project));
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
                "target/test-classes/com/example/InlineTestApi.class"));
    }

    private static boolean contains(byte[] bytes, int[] sequence) {
        for (int start = 0; start <= bytes.length - sequence.length; start++) {
            boolean matches = true;
            for (int offset = 0; offset < sequence.length; offset++) {
                if (sequence[offset] >= 0 && (bytes[start + offset] & 0xff) != sequence[offset]) {
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
        Path source = project.resolve("src/test/kotlin/com/example/InliningTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                @Suppress("NOTHING_TO_INLINE")
                inline fun doubled(value: Int): Int = value * 2

                object InlineTestApi {
                    @JvmStatic
                    fun answer(): Int = doubled(21)
                }

                class InliningTest {
                    @Test
                    fun preservesRuntimeBehavior() {
                        assertEquals(42, InlineTestApi.answer())
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean noInline) throws IOException {
        String compilerArguments = noInline
                ? "\"-parameters\", \"-Xno-inline\""
                : "\"-parameters\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-inlining"
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
