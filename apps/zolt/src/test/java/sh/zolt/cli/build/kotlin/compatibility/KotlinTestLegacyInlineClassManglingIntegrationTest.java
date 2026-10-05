package sh.zolt.cli.build.kotlin.compatibility;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Canonical CLI/worker proof for Kotlin 1.4 test inline-class mangling. */
final class KotlinTestLegacyInlineClassManglingIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void changesTestInlineClassLinkageOfflineAndInvalidatesWarmCompilation()
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
            String baselineMethod = assertMangling(project, cache);
            OutputBytes baselineBytes = outputBytes(project);

            CommandResult baselineWarm = test(project, cache);
            assertSuccessful(baselineWarm);
            assertTiming(baselineWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, true);
            CommandResult legacy = test(project, cache);
            assertSuccessful(legacy);
            assertTiming(legacy, "\"testCompilationMode\":\"full\"");
            String legacyMethod = assertMangling(project, cache);
            assertNotEquals(baselineMethod, legacyMethod);
            assertMethodAbsent(project, cache, baselineMethod);
            OutputBytes legacyBytes = outputBytes(project);
            assertFalse(Arrays.equals(baselineBytes.api(), legacyBytes.api()));
            assertArrayEquals(baselineBytes.valueClass(), legacyBytes.valueClass());

            CommandResult legacyWarm = test(project, cache);
            assertSuccessful(legacyWarm);
            assertTiming(legacyWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, false);
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            assertEquals(baselineMethod, assertMangling(project, cache));
            assertMethodAbsent(project, cache, legacyMethod);
            OutputBytes restoredBytes = outputBytes(project);
            assertArrayEquals(baselineBytes.api(), restoredBytes.api());
            assertArrayEquals(baselineBytes.valueClass(), restoredBytes.valueClass());
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

    private static String assertMangling(Path project, Path cache) throws Exception {
        try (URLClassLoader loader = loader(project, cache)) {
            Class<?> type = Class.forName("com.example.ManglingTestApi", true, loader);
            List<Method> methods = Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> method.getName().startsWith("render-"))
                    .toList();
            assertEquals(1, methods.size(), methods.toString());
            Method method = methods.getFirst();
            assertEquals("user:42", method.invoke(null, "user", "42"));
            return method.getName();
        }
    }

    private static void assertMethodAbsent(
            Path project,
            Path cache,
            String methodName) throws Exception {
        try (URLClassLoader loader = loader(project, cache)) {
            Class<?> type = Class.forName("com.example.ManglingTestApi", true, loader);
            assertThrows(
                    NoSuchMethodException.class,
                    () -> type.getDeclaredMethod(methodName, String.class, String.class));
        }
    }

    private static URLClassLoader loader(Path project, Path cache) throws Exception {
        URL[] urls = {
            project.resolve("target/test-classes").toUri().toURL(),
            kotlinStdlib(cache).toUri().toURL()
        };
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    private static Path kotlinStdlib(Path cache) throws IOException {
        String fileName = "kotlin-stdlib-" + KotlinCompilerCliFixture.KOTLIN_VERSION + ".jar";
        try (var paths = Files.walk(cache)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Kotlin stdlib was not cached under " + cache));
        }
    }

    private static OutputBytes outputBytes(Path project) throws IOException {
        Path output = project.resolve("target/test-classes/com/example");
        return new OutputBytes(
                Files.readAllBytes(output.resolve("ManglingTestApi.class")),
                Files.readAllBytes(output.resolve("TestUserId.class")));
    }

    private static void writeSource(Path project) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/ManglingTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                @JvmInline
                value class TestUserId(val value: String)

                object ManglingTestApi {
                    @JvmStatic
                    fun render(prefix: String, id: TestUserId): String = "$prefix:${id.value}"
                }

                class ManglingTest {
                    @Test
                    fun preservesRuntimeBehavior() {
                        assertEquals("user:42", ManglingTestApi.render("user", TestUserId("42")))
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean legacyMangling) throws IOException {
        String compilerArguments = legacyMangling
                ? "\"-parameters\", \"-Xuse-14-inline-classes-mangling-scheme\""
                : "\"-parameters\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-legacy-inline-class-mangling"
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

    private record OutputBytes(byte[] api, byte[] valueClass) {}
}
