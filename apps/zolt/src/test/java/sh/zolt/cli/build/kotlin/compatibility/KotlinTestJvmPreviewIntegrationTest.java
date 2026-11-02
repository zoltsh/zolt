package sh.zolt.cli.build.kotlin.compatibility;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

/** Canonical CLI/worker proof for test-only Kotlin JVM-preview output. */
final class KotlinTestJvmPreviewIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void runsPreviewMarkedTestsOfflineAndRestoresOrdinaryOutput() throws Exception {
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
            assertEquals(0, resolve.exitCode(), combined(resolve));
            repository.clearAuthorizations();
            repository.close();

            CommandResult baseline = test(project, cache);
            assertSuccessful(baseline);
            assertTiming(baseline, "\"testCompilationMode\":\"full\"");
            byte[] mainClass = mainClassBytes(project);
            byte[] baselineTestClass = testClassBytes(project);
            assertClassVersion(mainClass, 0);
            assertClassVersion(baselineTestClass, 0);

            CommandResult baselineWarm = test(project, cache);
            assertSuccessful(baselineWarm);
            assertTiming(baselineWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, true);
            CommandResult preview = test(project, cache);
            assertSuccessful(preview);
            assertTiming(preview, "\"testCompilationMode\":\"full\"");
            assertArrayEquals(mainClass, mainClassBytes(project));
            assertClassVersion(mainClassBytes(project), 0);
            assertClassVersion(testClassBytes(project), 0xffff);

            CommandResult previewWarm = test(project, cache);
            assertSuccessful(previewWarm);
            assertTiming(previewWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, false);
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            assertArrayEquals(baselineTestClass, testClassBytes(project));
            assertClassVersion(testClassBytes(project), 0);
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

    private static byte[] mainClassBytes(Path project) throws IOException {
        return Files.readAllBytes(project.resolve(
                "target/classes/com/example/PreviewMainApi.class"));
    }

    private static byte[] testClassBytes(Path project) throws IOException {
        return Files.readAllBytes(project.resolve(
                "target/test-classes/com/example/PreviewOnlyTest.class"));
    }

    private static void assertClassVersion(byte[] bytes, int expectedMinor) {
        assertTrue(bytes.length >= 8);
        assertEquals(expectedMinor, unsignedShort(bytes, 4));
        assertEquals(Runtime.version().feature() + 44, unsignedShort(bytes, 6));
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 8 | bytes[offset + 1] & 0xff;
    }

    private static void writeSources(Path project) throws IOException {
        Path main = project.resolve("src/main/kotlin/com/example/PreviewMainApi.kt");
        Path test = project.resolve("src/test/kotlin/com/example/PreviewOnlyTest.kt");
        Files.createDirectories(main.getParent());
        Files.createDirectories(test.getParent());
        Files.writeString(main, """
                package com.example

                object PreviewMainApi {
                    @JvmStatic
                    fun message(): String = "preview-test"
                }
                """);
        Files.writeString(test, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class PreviewOnlyTest {
                    @Test
                    fun loadsPreviewMarkedTestClass() {
                        assertEquals("preview-test", PreviewMainApi.message())
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean preview) throws IOException {
        String testCompilerArguments = preview
                ? "\"-parameters\", \"-Xjvm-enable-preview\""
                : "\"-parameters\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-jvm-preview"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [build]
                sources = ["src/main/kotlin"]

                [test.sources]
                kotlin = ["src/test/kotlin"]

                [toolchain.kotlin]
                version = "%s"

                [compiler]
                args = ["-parameters"]

                [compiler.test]
                args = [%s]

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
                testCompilerArguments,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
    }
}
