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

/** Canonical CLI/worker proof for JSpecify nullness severity in mixed test sources. */
final class KotlinTestJSpecifyAnnotationsIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void enforcesTestSeverityOfflineAndInvalidatesWarmCompilation() throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeSources(project);
            writeManifest(project, repository, "ignore", false);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();
            repository.close();

            CommandResult ignored = test(project, cache);
            assertSuccessful(ignored);
            assertTiming(ignored, "\"testCompilationMode\":\"full\"");

            CommandResult ignoredWarm = test(project, cache);
            assertSuccessful(ignoredWarm);
            assertTiming(ignoredWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, "warn", true);
            CommandResult warned = test(project, cache);
            assertEquals(1, warned.exitCode(), combined(warned));
            assertTrue(diagnostics(warned).contains("warnings found"), combined(warned));
            assertTrue(diagnostics(warned).contains("nullable receiver"), combined(warned));

            writeManifest(project, repository, "strict", false);
            CommandResult strict = test(project, cache);
            assertEquals(1, strict.exitCode(), combined(strict));
            assertTrue(diagnostics(strict).contains("nullable receiver"), combined(strict));

            writeManifest(project, repository, "ignore", false);
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");

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

    private static void writeSources(Path project) throws IOException {
        writeSource(project, "src/test/java/org/jspecify/annotations/Nullable.java", """
                package org.jspecify.annotations;

                import java.lang.annotation.Documented;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Documented
                @Target(ElementType.TYPE_USE)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Nullable {}
                """);
        writeSource(project, "src/test/java/com/example/JavaApi.java", """
                package com.example;

                import org.jspecify.annotations.Nullable;

                public final class JavaApi {
                    private JavaApi() {}

                    public static @Nullable String maybe() {
                        return null;
                    }
                }
                """);
        writeSource(project, "src/test/kotlin/com/example/JSpecifyTest.kt", """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                object JSpecifyTestApi {
                    @JvmStatic
                    fun unsafeLength(): Int = JavaApi.maybe().length
                }

                class JSpecifyTest {
                    @Test
                    fun remainsRunnable() {
                        assertEquals("Zolt", "Zolt")
                    }
                }
                """);
    }

    private static void writeSource(
            Path project,
            String relativePath,
            String content) throws IOException {
        Path source = project.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            String jspecifyMode,
            boolean warningsAsErrors) throws IOException {
        String compilerArguments = warningsAsErrors
                ? "\"-Werror\", \"-Xjspecify-annotations=%s\"".formatted(jspecifyMode)
                : "\"-Xjspecify-annotations=%s\"".formatted(jspecifyMode);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-jspecify-annotations"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [compiler.test]
                args = [%s]

                [test.sources]
                java = ["src/test/java"]
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
