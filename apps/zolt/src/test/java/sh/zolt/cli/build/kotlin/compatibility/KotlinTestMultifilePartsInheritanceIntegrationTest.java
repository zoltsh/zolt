package sh.zolt.cli.build.kotlin.compatibility;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Canonical CLI/worker proof for Kotlin test multifile-part inheritance. */
final class KotlinTestMultifilePartsInheritanceIntegrationTest {
    private static final String FACADE = "com.example.Utilities";
    private static final String PART_ONE = "com.example.Utilities__PartOneKt";
    private static final String PART_TWO = "com.example.Utilities__PartTwoKt";

    @TempDir
    private Path tempDir;

    @Test
    void changesTestMultifileHierarchyOfflineAndInvalidatesWarmCompilation()
            throws Exception {
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
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();
            repository.close();

            CommandResult baseline = test(project, cache);
            assertSuccessful(baseline);
            assertTiming(baseline, "\"testCompilationMode\":\"full\"");
            assertLayout(project, cache, false);
            OutputBytes baselineBytes = outputBytes(project);

            CommandResult baselineWarm = test(project, cache);
            assertSuccessful(baselineWarm);
            assertTiming(baselineWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, true);
            CommandResult inherited = test(project, cache);
            assertSuccessful(inherited);
            assertTiming(inherited, "\"testCompilationMode\":\"full\"");
            assertLayout(project, cache, true);
            OutputBytes inheritedBytes = outputBytes(project);
            assertFalse(Arrays.equals(baselineBytes.facade(), inheritedBytes.facade()));
            assertFalse(Arrays.equals(baselineBytes.partOne(), inheritedBytes.partOne()));
            assertFalse(Arrays.equals(baselineBytes.partTwo(), inheritedBytes.partTwo()));

            CommandResult inheritedWarm = test(project, cache);
            assertSuccessful(inheritedWarm);
            assertTiming(inheritedWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, false);
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            assertLayout(project, cache, false);
            OutputBytes restoredBytes = outputBytes(project);
            assertArrayEquals(baselineBytes.facade(), restoredBytes.facade());
            assertArrayEquals(baselineBytes.partOne(), restoredBytes.partOne());
            assertArrayEquals(baselineBytes.partTwo(), restoredBytes.partTwo());
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

    private static void assertLayout(
            Path project,
            Path cache,
            boolean inheritedParts) throws Exception {
        URL[] urls = {
            project.resolve("target/test-classes").toUri().toURL(),
            kotlinStdlib(cache).toUri().toURL()
        };
        try (URLClassLoader loader = new URLClassLoader(
                urls,
                ClassLoader.getPlatformClassLoader())) {
            Class<?> facade = Class.forName(FACADE, true, loader);
            Class<?> partOne = Class.forName(PART_ONE, true, loader);
            Class<?> partTwo = Class.forName(PART_TWO, true, loader);

            assertEquals("first", invokeFacadeMethod(facade, "first"));
            assertEquals("second", invokeFacadeMethod(facade, "second"));
            if (inheritedParts) {
                assertEquals(partTwo, facade.getSuperclass());
                assertEquals(partOne, partTwo.getSuperclass());
                assertEquals(Object.class, partOne.getSuperclass());
                assertThrows(NoSuchMethodException.class, () -> facade.getDeclaredMethod("first"));
                assertThrows(NoSuchMethodException.class, () -> facade.getDeclaredMethod("second"));
            } else {
                assertEquals(Object.class, facade.getSuperclass());
                assertEquals(Object.class, partOne.getSuperclass());
                assertEquals(Object.class, partTwo.getSuperclass());
                assertEquals("first", facade.getDeclaredMethod("first").invoke(null));
                assertEquals("second", facade.getDeclaredMethod("second").invoke(null));
            }
        }
    }

    private static Object invokeFacadeMethod(Class<?> facade, String methodName) throws Exception {
        Method method = facade.getMethod(methodName);
        assertTrue(method.trySetAccessible());
        return method.invoke(null);
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
                Files.readAllBytes(output.resolve("Utilities.class")),
                Files.readAllBytes(output.resolve("Utilities__PartOneKt.class")),
                Files.readAllBytes(output.resolve("Utilities__PartTwoKt.class")));
    }

    private static void writeSources(Path project) throws IOException {
        Path sourceRoot = project.resolve("src/test/kotlin/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("PartOne.kt"), """
                @file:JvmName("Utilities")
                @file:JvmMultifileClass

                package com.example

                fun first(): String = "first"
                """);
        Files.writeString(sourceRoot.resolve("PartTwo.kt"), """
                @file:JvmName("Utilities")
                @file:JvmMultifileClass

                package com.example

                fun second(): String = "second"
                """);
        Files.writeString(sourceRoot.resolve("MultifilePartsTest.kt"), """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class MultifilePartsTest {
                    @Test
                    fun preservesRuntimeBehavior() {
                        assertEquals("first:second", first() + ":" + second())
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean inheritParts) throws IOException {
        String compilerArguments = inheritParts
                ? "\"-parameters\", \"-Xmultifile-parts-inherit\""
                : "\"-parameters\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-multifile-parts-inheritance"
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

    private record OutputBytes(byte[] facade, byte[] partOne, byte[] partTwo) {}
}
