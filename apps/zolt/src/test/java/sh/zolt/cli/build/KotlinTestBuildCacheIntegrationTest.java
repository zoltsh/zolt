package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** Real-compiler proof for mixed Java/Kotlin test compilation and build-cache restoration. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class KotlinTestBuildCacheIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void restoresMixedTestOutputsAndRebuildsBothCompilerPhasesSafely() throws Exception {
        assumeTrue(System.getenv("ZOLT_USER_HOME") == null, "test needs an isolated user.home fallback");
        String previousUserHome = System.getProperty("user.home");
        Path fakeUserHome = tempDir.resolve("fake-user-home");
        System.setProperty("user.home", fakeUserHome.toString());
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path project = tempDir.resolve("project");
            Path onlineCache = tempDir.resolve("online-cache");
            Path artifactCache = tempDir.resolve("artifact-cache");
            Path buildCache = configureBuildCache(fakeUserHome);
            seed(repository, project, onlineCache, artifactCache);

            CommandResult first = test(project, artifactCache);
            assertEquals(0, first.exitCode(), first.stderr());
            assertTrue(first.stdout().contains("Tests passed"), first.stdout());
            assertCountPhrase(first.stdout(), 2, "test source files");
            assertSuccessfulTests(first, 2);
            Path mainOutput = project.resolve("target/classes");
            Path testOutput = project.resolve("target/test-classes");
            Path kotlinTestClass = testOutput.resolve("com/example/MainTest.class");
            Path kotlinMarkerClass = testOutput.resolve("com/example/KotlinCompileMarker.class");
            Path javaTestClass = testOutput.resolve("com/example/JavaCycleTest.class");
            Path obsoleteTestClass = testOutput.resolve("com/example/ObsoleteTestSupport.class");
            assertTrue(Files.isRegularFile(kotlinTestClass));
            assertTrue(Files.isRegularFile(javaTestClass));
            assertTrue(Files.isRegularFile(obsoleteTestClass));
            Path mainModule = kotlinModule(mainOutput);
            Path testModule = kotlinModule(testOutput);
            assertNotEquals(mainModule.getFileName(), testModule.getFileName());
            byte[] kotlinTestClassBytes = Files.readAllBytes(kotlinTestClass);
            byte[] kotlinMarkerClassBytes = Files.readAllBytes(kotlinMarkerClass);
            byte[] javaTestClassBytes = Files.readAllBytes(javaTestClass);
            Map<String, String> compiledOutput = compiledOutputSnapshot(mainOutput, testOutput);
            FileTime kotlinTestClassTime = Files.getLastModifiedTime(kotlinTestClass);
            FileTime javaTestClassTime = Files.getLastModifiedTime(javaTestClass);
            Map<String, String> storedMetadata = cacheMetadata(buildCache);
            assertTrue(
                    storedMetadata.values().stream().anyMatch(value -> value.contains("scope=main\n")),
                    storedMetadata.toString());
            assertTrue(
                    storedMetadata.values().stream().anyMatch(value -> value.contains("scope=test\n")),
                    storedMetadata.toString());

            CommandResult warm = test(project, artifactCache);

            assertEquals(0, warm.exitCode(), warm.stderr());
            assertSuccessfulTests(warm, 2);
            assertTiming(warm, "build test inputs", "\"mainCompilationMode\":\"skipped\"");
            assertTiming(warm, "compile test sources", "\"testCompilationMode\":\"skipped\"");
            assertArrayEquals(kotlinTestClassBytes, Files.readAllBytes(kotlinTestClass));
            assertArrayEquals(javaTestClassBytes, Files.readAllBytes(javaTestClass));
            assertEquals(kotlinTestClassTime, Files.getLastModifiedTime(kotlinTestClass));
            assertEquals(javaTestClassTime, Files.getLastModifiedTime(javaTestClass));

            deleteTree(project.resolve("target"));
            CommandResult restored = test(project, artifactCache);

            assertEquals(0, restored.exitCode(), restored.stderr());
            assertTrue(restored.stdout().contains("Tests passed"), restored.stdout());
            assertSuccessfulTests(restored, 2);
            assertTiming(restored, "build test inputs", "\"mainCompilationMode\":\"restored\"");
            assertTiming(restored, "compile test sources", "\"testCompilationMode\":\"restored\"");
            assertEquals(compiledOutput, compiledOutputSnapshot(mainOutput, testOutput));
            assertFalse(Files.exists(mainOutput.resolve(".zolt-incremental-main.state")));
            assertFalse(Files.exists(testOutput.resolve(".zolt-incremental-test.state")));

            writeKotlinTest(project, "after-restore");
            writeBrokenJavaTest(project);
            CommandResult brokenJava = test(project, artifactCache);

            assertEquals(1, brokenJava.exitCode());
            assertTrue(brokenJava.stderr().contains("cannot find symbol"), brokenJava.stderr());
            assertTrue(brokenJava.stderr().contains("Missing"), brokenJava.stderr());
            assertFalse(
                    java.util.Arrays.equals(kotlinMarkerClassBytes, Files.readAllBytes(kotlinMarkerClass)),
                    "the changed Kotlin source must be emitted before javac fails");
            assertTrue(Files.isRegularFile(kotlinModule(testOutput)));
            assertFalse(Files.exists(javaTestClass), "failed javac must not leave its prior class");
            assertFalse(Files.exists(obsoleteTestClass), "full compilation must clean stale classes first");

            writeRepairedJavaTest(project);
            CommandResult rebuilt = test(project, artifactCache);

            assertEquals(0, rebuilt.exitCode(), rebuilt.stderr());
            assertTrue(rebuilt.stdout().contains("Tests passed"), rebuilt.stdout());
            assertSuccessfulTests(rebuilt, 2);
            assertTiming(rebuilt, "build test inputs", "\"mainCompilationMode\":\"skipped\"");
            assertTiming(rebuilt, "compile test sources", "\"testCompilationMode\":\"full\"");
            assertTiming(
                    rebuilt,
                    "compile test sources",
                    "\"testIncrementalFallbackReason\":\"kotlin-test-sources\"");
            assertFalse(java.util.Arrays.equals(javaTestClassBytes, Files.readAllBytes(javaTestClass)));
            assertFalse(Files.exists(obsoleteTestClass), "the removed secondary Java type must stay absent");
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "post-seed commands must not contact the repository");
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private static void seed(
            CliTestRepository repository,
            Path project,
            Path onlineCache,
            Path artifactCache) throws IOException {
        KotlinCompilerCliFixture.publish(repository);
        JUnitConsoleCliFixture.publish(repository);
        writeProject(project, repository);
        CommandResult resolve = execute(
                "resolve",
                "--cwd", project.toString(),
                "--cache-root", onlineCache.toString());
        assertEquals(0, resolve.exitCode(), resolve.stderr());
        Files.move(onlineCache, artifactCache);
        repository.clearAuthorizations();
    }

    private static void writeProject(Path project, CliTestRepository repository) throws IOException {
        Files.createDirectories(project.resolve("src/main/kotlin/com/example"));
        Files.createDirectories(project.resolve("src/test/java/com/example"));
        Files.createDirectories(project.resolve("src/test/kotlin/com/example"));
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-cache"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "%s"

                [test.sources]
                kotlin = ["src/test/kotlin"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"

                [dependencies.test]
                "org.junit.platform:junit-platform-console-standalone" = "%s"
                """.formatted(
                currentJavaMajorVersion(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
        Files.writeString(project.resolve("src/main/kotlin/com/example/Main.kt"), """
                package com.example

                internal object Main {
                    @JvmStatic
                    fun message(): String = "hello"
                }
                """);
        writeKotlinTest(project, "initial");
        writeInitialJavaTest(project);
    }

    private static void writeKotlinTest(Path project, String revision) throws IOException {
        Files.writeString(project.resolve("src/test/kotlin/com/example/MainTest.kt"), """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class MainTest {
                    @Test
                    fun seesJavaHalfOfCycle() {
                        assertEquals("hello", JavaCycleTest.javaMessage())
                    }

                    companion object {
                        @JvmStatic
                        fun kotlinMessage(): String = Main.message()
                    }
                }

                internal object KotlinCompileMarker {
                    @JvmStatic
                    fun value(): String = "%s"
                }
                """.formatted(revision));
    }

    private static void writeInitialJavaTest(Path project) throws IOException {
        writeJavaTest(project, """
                return MainTest.kotlinMessage();
                """, """

                final class ObsoleteTestSupport {}
                """);
    }

    private static void writeBrokenJavaTest(Path project) throws IOException {
        writeJavaTest(project, """
                Missing.symbol();
                return MainTest.kotlinMessage();
                """, """

                final class ObsoleteTestSupport {}
                """);
    }

    private static void writeRepairedJavaTest(Path project) throws IOException {
        writeJavaTest(project, """
                String message = MainTest.kotlinMessage();
                return message;
                """, "");
    }

    private static void writeJavaTest(
            Path project,
            String javaMessageBody,
            String secondaryType) throws IOException {
        Files.writeString(project.resolve("src/test/java/com/example/JavaCycleTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                public final class JavaCycleTest {
                    @Test
                    void seesKotlinHalfOfCycle() {
                        assertEquals("hello", MainTest.kotlinMessage());
                    }

                    static String javaMessage() {
                %s
                    }
                }
                %s
                """.formatted(javaMessageBody.indent(8).stripTrailing(), secondaryType));
    }

    private static Path configureBuildCache(Path fakeUserHome) throws IOException {
        Path globalDirectory = fakeUserHome.resolve(".zolt");
        Files.createDirectories(globalDirectory);
        Files.writeString(globalDirectory.resolve("config.toml"), """
                version = 1

                [buildCache]
                enabled = true
                dir = "build-cache"
                """);
        return globalDirectory.resolve("build-cache");
    }

    private static CommandResult test(Path project, Path artifactCache) {
        return execute(
                "test",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString());
    }

    private static Path kotlinModule(Path output) throws IOException {
        try (Stream<Path> paths = Files.walk(output.resolve("META-INF"))) {
            var modules = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".kotlin_module"))
                    .toList();
            assertEquals(
                    1,
                    modules.size(),
                    "Kotlin output must contain exactly one module metadata file");
            return modules.getFirst();
        }
    }

    private static Map<String, String> cacheMetadata(Path buildCache) throws IOException {
        Map<String, String> metadata = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(buildCache)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".zbc.meta"))
                    .toList()) {
                metadata.put(buildCache.relativize(path).toString(), Files.readString(path));
            }
        }
        return Map.copyOf(metadata);
    }

    private static Map<String, String> compiledOutputSnapshot(Path... roots) throws IOException {
        Map<String, String> snapshot = new TreeMap<>();
        for (Path root : roots) {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile)
                        .filter(KotlinTestBuildCacheIntegrationTest::isCompiledOutput)
                        .toList()) {
                    String key = root.getFileName() + "/" + root.relativize(path);
                    snapshot.put(
                            key,
                            Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
                }
            }
        }
        return Map.copyOf(snapshot);
    }

    private static boolean isCompiledOutput(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".class") || name.endsWith(".kotlin_module");
    }

    private static void assertTiming(CommandResult result, String phase, String expected) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"" + phase + "\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing timing phase " + phase + " in:\n" + result.stderr()));
        assertTrue(line.contains(expected), line);
    }

    private static void assertSuccessfulTests(CommandResult result, int expected) {
        assertCountPhrase(result.stdout(), expected, "tests found");
        assertCountPhrase(result.stdout(), expected, "tests successful");
    }

    private static void assertCountPhrase(String output, int expected, String phrase) {
        assertTrue(
                output.matches("(?s).*\\b" + expected + " " + phrase + "\\b.*"),
                output);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
