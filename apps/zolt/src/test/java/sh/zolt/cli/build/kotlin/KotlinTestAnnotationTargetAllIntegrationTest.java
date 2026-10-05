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

/** Canonical CLI/worker proof for Kotlin test all-target annotations. */
final class KotlinTestAnnotationTargetAllIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void compilesAndExecutesAllTargetOnlyWhenTestPreviewIsEnabled() throws Exception {
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

            CommandResult disabled = test(project, cache);
            assertEquals(1, disabled.exitCode(), combined(disabled));
            assertTrue(diagnostics(disabled).contains("-xannotation-target-all"), combined(disabled));

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
            assertTrue(diagnostics(removed).contains("-xannotation-target-all"), combined(removed));

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

    private static void writeSource(Path project) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/AnnotationTargetAllTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertTrue
                import org.junit.jupiter.api.Test

                @Target(
                    AnnotationTarget.PROPERTY,
                    AnnotationTarget.FIELD,
                    AnnotationTarget.VALUE_PARAMETER,
                    AnnotationTarget.PROPERTY_GETTER,
                )
                @Retention(AnnotationRetention.RUNTIME)
                annotation class Spread

                class Annotated(@all:Spread var value: String)

                class AnnotationTargetAllTest {
                    @Test
                    fun propagatesToEveryJvmSite() {
                        val type = Annotated::class.java
                        assertTrue(type.getDeclaredConstructor(String::class.java)
                            .parameters[0]
                            .isAnnotationPresent(Spread::class.java))
                        assertTrue(type.getDeclaredField("value")
                            .isAnnotationPresent(Spread::class.java))
                        assertTrue(type.getDeclaredMethod("getValue")
                            .isAnnotationPresent(Spread::class.java))
                        assertTrue(type.getDeclaredMethod("setValue", String::class.java)
                            .parameters[0]
                            .isAnnotationPresent(Spread::class.java))
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean preview) throws IOException {
        String compilerArguments = preview
                ? "\"-parameters\", \"-Xannotation-target-all\""
                : "\"-parameters\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-annotation-target-all"
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
