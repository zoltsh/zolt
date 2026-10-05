package sh.zolt.cli.build.kotlin;

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

/** Canonical CLI/worker proof for Checker Framework compatqual handling in mixed tests. */
final class KotlinTestCompatqualAnnotationsIntegrationTest {
    private static final String OPTION =
            "-Xsupport-compatqual-checker-framework-annotations=";

    @TempDir
    private Path tempDir;

    @Test
    void controlsTestCompatqualNullnessOfflineAndInvalidatesWarmCompilation()
            throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeSources(project);
            writeManifest(project, repository, "disable");

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();
            repository.close();

            CommandResult disabled = test(project, cache);
            assertSuccessful(disabled);
            assertTiming(disabled, "\"testCompilationMode\":\"full\"");
            byte[] disabledBytes = Files.readAllBytes(classFile(project));

            CommandResult disabledWarm = test(project, cache);
            assertSuccessful(disabledWarm);
            assertTiming(disabledWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, "");
            CommandResult defaultMode = test(project, cache);
            assertEquals(1, defaultMode.exitCode(), combined(defaultMode));
            assertTrue(diagnostics(defaultMode).contains("nullable receiver"), combined(defaultMode));

            writeManifest(project, repository, "enable");
            CommandResult enabled = test(project, cache);
            assertEquals(1, enabled.exitCode(), combined(enabled));
            assertTrue(diagnostics(enabled).contains("nullable receiver"), combined(enabled));

            writeManifest(project, repository, "disable");
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            assertArrayEquals(disabledBytes, Files.readAllBytes(classFile(project)));

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

    private static Path classFile(Path project) {
        return project.resolve("target/test-classes/com/example/CompatqualTestApi.class");
    }

    private static void writeSources(Path project) throws IOException {
        writeSource(
                project,
                "src/test/java/org/checkerframework/checker/nullness/compatqual/NullableDecl.java",
                """
                package org.checkerframework.checker.nullness.compatqual;

                import java.lang.annotation.Documented;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Documented
                @Target({
                    ElementType.TYPE_USE,
                    ElementType.METHOD,
                    ElementType.PARAMETER,
                    ElementType.FIELD
                })
                @Retention(RetentionPolicy.RUNTIME)
                public @interface NullableDecl {}
                """);
        writeSource(project, "src/test/java/com/example/LegacyTestApi.java", """
                package com.example;

                import org.checkerframework.checker.nullness.compatqual.NullableDecl;

                public final class LegacyTestApi {
                    private LegacyTestApi() {}

                    @NullableDecl
                    public static String value() {
                        return "compat";
                    }
                }
                """);
        writeSource(project, "src/test/kotlin/com/example/CompatqualTest.kt", """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                object CompatqualTestApi {
                    @JvmStatic
                    fun length(): Int = LegacyTestApi.value().length
                }

                class CompatqualTest {
                    @Test
                    fun preservesRuntimeBehavior() {
                        assertEquals(6, CompatqualTestApi.length())
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
            String compatqualMode) throws IOException {
        String compilerArguments = compatqualMode.isEmpty()
                ? "\"-parameters\""
                : "\"-parameters\", \"%s%s\"".formatted(OPTION, compatqualMode);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-compatqual-annotations"
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
