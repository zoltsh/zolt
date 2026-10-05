package sh.zolt.cli.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
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

/** Canonical CLI/worker proof for boxed Kotlin test value classes. */
final class KotlinTestJvmExposeBoxedIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void changesTestJavaInteropSurfaceAndInvalidatesWarmCompilation() throws Exception {
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
            assertEquals(new Surface(false, false), surface(project));

            CommandResult baselineWarm = test(project, cache);
            assertSuccessful(baselineWarm);
            assertTiming(baselineWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, true);
            CommandResult preview = test(project, cache);
            assertSuccessful(preview);
            assertTiming(preview, "\"testCompilationMode\":\"full\"");
            assertEquals(new Surface(true, true), surface(project));

            CommandResult previewWarm = test(project, cache);
            assertSuccessful(previewWarm);
            assertTiming(previewWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, false);
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            assertEquals(new Surface(false, false), surface(project));
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

    private static Surface surface(Path project) throws Exception {
        URL output = project.resolve("target/test-classes").toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {output},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> testId = Class.forName("com.example.TestId", false, loader);
            Class<?> api = Class.forName("com.example.JvmExposeBoxedTestKt", false, loader);
            boolean publicConstructor = Arrays.stream(testId.getConstructors())
                    .anyMatch(constructor -> Arrays.equals(
                            constructor.getParameterTypes(),
                            new Class<?>[] {String.class}));
            boolean boxedFunction = Arrays.stream(api.getMethods())
                    .anyMatch(method -> method.getName().equals("echo")
                            && method.getReturnType().equals(testId)
                            && Arrays.equals(
                                    method.getParameterTypes(),
                                    new Class<?>[] {testId}));
            return new Surface(publicConstructor, boxedFunction);
        }
    }

    private static void writeSource(Path project) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/JvmExposeBoxedTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                @JvmInline
                value class TestId(val value: String)

                fun echo(value: TestId): TestId = value

                class JvmExposeBoxedTest {
                    @Test
                    fun preservesKotlinBehavior() {
                        assertEquals("Zolt", echo(TestId("Zolt")).value)
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean preview) throws IOException {
        String compilerArguments = preview
                ? "\"-parameters\", \"-Xjvm-expose-boxed\""
                : "\"-parameters\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-jvm-expose-boxed"
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

    private record Surface(boolean publicConstructor, boolean boxedFunction) {
    }
}
