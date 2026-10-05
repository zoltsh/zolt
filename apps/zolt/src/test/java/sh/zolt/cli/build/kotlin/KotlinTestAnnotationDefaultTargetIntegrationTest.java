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

/** Canonical CLI/worker proof for Kotlin test annotation default-target modes. */
final class KotlinTestAnnotationDefaultTargetIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void changesTestAnnotationPlacementAndInvalidatesWarmCompilation() throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeSource(project);
            writeManifest(project, repository, "first-only");

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();
            repository.close();

            CommandResult firstOnly = test(project, cache);
            assertSuccessful(firstOnly);
            assertTiming(firstOnly, "\"testCompilationMode\":\"full\"");
            assertPlacement(project, true, false);

            CommandResult firstOnlyWarm = test(project, cache);
            assertSuccessful(firstOnlyWarm);
            assertTiming(firstOnlyWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, "param-property");
            CommandResult paramProperty = test(project, cache);
            assertSuccessful(paramProperty);
            assertTiming(paramProperty, "\"testCompilationMode\":\"full\"");
            assertPlacement(project, true, true);

            CommandResult paramPropertyWarm = test(project, cache);
            assertSuccessful(paramPropertyWarm);
            assertTiming(paramPropertyWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, "first-only");
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            assertPlacement(project, true, false);
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

    private static void assertPlacement(
            Path project,
            boolean expectedParameter,
            boolean expectedField) throws Exception {
        URL output = project.resolve("target/test-classes").toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {output},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> annotated = Class.forName("com.example.Annotated", false, loader);
            Class<? extends Annotation> marker = Class.forName("com.example.Marker", false, loader)
                    .asSubclass(Annotation.class);
            boolean parameter = annotated.getDeclaredConstructor(String.class)
                    .getParameters()[0]
                    .isAnnotationPresent(marker);
            boolean field = annotated.getDeclaredField("value")
                    .isAnnotationPresent(marker);
            assertEquals(expectedParameter, parameter);
            assertEquals(expectedField, field);
        }
    }

    private static void writeSource(Path project) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/AnnotationDefaultTargetTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertTrue
                import org.junit.jupiter.api.Test

                @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
                @Retention(AnnotationRetention.RUNTIME)
                annotation class Marker

                class Annotated(@Marker val value: String)

                class AnnotationDefaultTargetTest {
                    @Test
                    fun retainsParameterTarget() {
                        val constructor = Annotated::class.java
                            .getDeclaredConstructor(String::class.java)
                        assertTrue(constructor.parameters[0]
                            .isAnnotationPresent(Marker::class.java))
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            String mode) throws IOException {
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-annotation-default-target"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [compiler.test]
                args = ["-parameters", "-Xannotation-default-target=%s"]

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
                mode,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
    }
}
