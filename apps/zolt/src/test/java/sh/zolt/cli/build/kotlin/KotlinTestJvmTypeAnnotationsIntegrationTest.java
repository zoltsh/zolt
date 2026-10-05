package sh.zolt.cli.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Canonical CLI/worker proof for Kotlin test type-use annotations. */
final class KotlinTestJvmTypeAnnotationsIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void changesReflectedTestReturnTypeAndInvalidatesWarmCompilation() throws Exception {
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
            assertReturnTypeTagged(project, false);

            CommandResult baselineWarm = test(project, cache);
            assertSuccessful(baselineWarm);
            assertTiming(baselineWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, true);
            CommandResult enabled = test(project, cache);
            assertSuccessful(enabled);
            assertTiming(enabled, "\"testCompilationMode\":\"full\"");
            assertReturnTypeTagged(project, true);

            CommandResult enabledWarm = test(project, cache);
            assertSuccessful(enabledWarm);
            assertTiming(enabledWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, false);
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            assertReturnTypeTagged(project, false);
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

    private static void assertReturnTypeTagged(Path project, boolean expected) throws Exception {
        URL output = project.resolve("target/test-classes").toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {output},
                ClassLoader.getPlatformClassLoader())) {
            Class<? extends Annotation> tagged = Class.forName(
                            "com.example.TestTagged",
                            false,
                            loader)
                    .asSubclass(Annotation.class);
            boolean actual = Class.forName("com.example.TypeAnnotationApi", false, loader)
                    .getDeclaredMethod("value")
                    .getAnnotatedReturnType()
                    .isAnnotationPresent(tagged);
            assertEquals(expected, actual);
        }
    }

    private static void writeSource(Path project) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/JvmTypeAnnotationsTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                @Target(AnnotationTarget.TYPE)
                @Retention(AnnotationRetention.RUNTIME)
                annotation class TestTagged

                class TypeAnnotationApi {
                    fun value(): @TestTagged String = "Zolt"
                }

                class JvmTypeAnnotationsTest {
                    @Test
                    fun preservesKotlinBehavior() {
                        assertEquals("Zolt", TypeAnnotationApi().value())
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean emitTypeAnnotations) throws IOException {
        String compilerArguments = emitTypeAnnotations
                ? "\"-parameters\", \"-Xemit-jvm-type-annotations\""
                : "\"-parameters\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-jvm-type-annotations"
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
