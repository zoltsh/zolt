package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** CLI canary for explicit Kotlin unit-test roots, real compilation, and JUnit execution. */
final class TestCommandKotlinIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolvesCompilesExecutesAndReusesKotlinTestsFromSeededCache() throws Exception {
        Path projectDirectory = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");

        try (CliTestRepository repository = CliTestRepository.start()) {
            seed(repository, projectDirectory, onlineCache, offlineCache, false);

            CommandResult first = testFromSeededCache(projectDirectory, offlineCache);
            Path classFile = projectDirectory.resolve("target/test-classes/com/example/DemoTest.class");

            assertEquals(0, first.exitCode(), first.stderr());
            assertTrue(first.stdout().contains("Tests passed"), first.stdout());
            assertTrue(first.stdout().contains("1 test source files"), first.stdout());
            assertTrue(Files.isRegularFile(classFile));
            Path moduleFile = kotlinModule(projectDirectory.resolve("target/test-classes"));
            assertTrue(Files.isRegularFile(moduleFile));
            byte[] firstClass = Files.readAllBytes(classFile);
            FileTime firstClassTime = Files.getLastModifiedTime(classFile);

            CommandResult warm = testFromSeededCache(projectDirectory, offlineCache);

            assertEquals(0, warm.exitCode(), warm.stderr());
            assertTrue(warm.stdout().contains("Tests passed"), warm.stdout());
            assertArrayEquals(firstClass, Files.readAllBytes(classFile));
            assertEquals(firstClassTime, Files.getLastModifiedTime(classFile));

            writeKotlinTest(projectDirectory, "hello", "hello");
            CommandResult changed = testFromSeededCache(projectDirectory, offlineCache);

            assertEquals(0, changed.exitCode(), changed.stderr());
            assertTrue(changed.stdout().contains("Tests passed"), changed.stdout());
            assertFalse(java.util.Arrays.equals(firstClass, Files.readAllBytes(classFile)));

            byte[] classBeforeFailure = Files.readAllBytes(classFile);
            byte[] moduleBeforeFailure = Files.readAllBytes(moduleFile);
            repository.close();
            deleteCompilerJars(offlineCache);
            writeKotlinTest(projectDirectory, "hello", "missing-compiler");

            CommandResult missingCompiler = testFromSeededCache(projectDirectory, offlineCache);

            assertEquals(1, missingCompiler.exitCode());
            assertTrue(
                    missingCompiler.stderr().contains(
                            "org.jetbrains.kotlin:kotlin-compiler-embeddable:"
                                    + KotlinCompilerCliFixture.KOTLIN_VERSION),
                    missingCompiler.stderr());
            assertArrayEquals(classBeforeFailure, Files.readAllBytes(classFile));
            assertArrayEquals(moduleBeforeFailure, Files.readAllBytes(moduleFile));
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "post-seed test commands must not contact the repository");
        }
    }

    @Test
    void mixedMainSupportsKotlinAndJavaTestsWithoutRepositoryAccess() throws Exception {
        Path projectDirectory = tempDir.resolve("kotlin-main-project");
        Path onlineCache = tempDir.resolve("kotlin-main-online-cache");
        Path offlineCache = tempDir.resolve("kotlin-main-offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            seed(repository, projectDirectory, onlineCache, offlineCache, true);
            repository.close();
            CommandResult first = testFromSeededCache(projectDirectory, offlineCache);
            Path mainClass = projectDirectory.resolve("target/classes/com/example/Main.class");
            Path javaMainClass = projectDirectory.resolve("target/classes/com/example/JavaMain.class");
            Path kotlinApiClass = projectDirectory.resolve("target/classes/com/example/KotlinApi.class");
            Path testClass = projectDirectory.resolve("target/test-classes/com/example/DemoTest.class");
            assertEquals(0, first.exitCode(), first.stderr());
            assertTrue(first.stdout().contains("Tests passed"), first.stdout());
            assertTrue(Files.isRegularFile(javaMainClass));
            assertTrue(Files.isRegularFile(kotlinApiClass));
            Path mainModule = kotlinModule(projectDirectory.resolve("target/classes"));
            Path testModule = kotlinModule(projectDirectory.resolve("target/test-classes"));
            assertNotEquals(mainModule.getFileName(), testModule.getFileName());
            byte[] stableMainClass = Files.readAllBytes(mainClass);
            byte[] stableJavaMainClass = Files.readAllBytes(javaMainClass);
            FileTime mainClassTime = Files.getLastModifiedTime(mainClass);
            FileTime javaMainClassTime = Files.getLastModifiedTime(javaMainClass);
            FileTime testClassTime = Files.getLastModifiedTime(testClass);
            CommandResult warm = testFromSeededCache(projectDirectory, offlineCache);
            assertEquals(0, warm.exitCode(), warm.stderr());
            assertEquals(mainClassTime, Files.getLastModifiedTime(mainClass));
            assertEquals(javaMainClassTime, Files.getLastModifiedTime(javaMainClass));
            assertEquals(testClassTime, Files.getLastModifiedTime(testClass));
            writeKotlinMain(projectDirectory, "Int");
            CommandResult incompatible = testFromSeededCache(projectDirectory, offlineCache);
            assertEquals(1, incompatible.exitCode());
            assertTrue(
                    incompatible.stderr().contains("Int")
                            && incompatible.stderr().contains("String"),
                    incompatible.stderr());
            assertArrayEquals(stableMainClass, Files.readAllBytes(mainClass));
            assertArrayEquals(stableJavaMainClass, Files.readAllBytes(javaMainClass));
            writeKotlinMain(projectDirectory, "String");
            CommandResult repaired = testFromSeededCache(projectDirectory, offlineCache);
            assertEquals(0, repaired.exitCode(), repaired.stderr());
            assertTrue(repaired.stdout().contains("Tests passed"), repaired.stdout());

            byte[] kotlinTestBeforeMixedRejection = Files.readAllBytes(testClass);
            writeJavaTest(projectDirectory);
            CommandResult mixedTests = testFromSeededCache(projectDirectory, offlineCache);
            assertEquals(1, mixedTests.exitCode());
            assertTrue(
                    mixedTests.stderr().contains(
                            "The test source set combines Java and Kotlin, which the Kotlin preview does not support."),
                    mixedTests.stderr());
            assertArrayEquals(kotlinTestBeforeMixedRejection, Files.readAllBytes(testClass));

            Files.delete(projectDirectory.resolve("src/test/kotlin/com/example/DemoTest.kt"));
            CommandResult javaOnly = testFromSeededCache(projectDirectory, offlineCache);
            assertEquals(0, javaOnly.exitCode(), javaOnly.stderr());
            assertTrue(javaOnly.stdout().contains("Tests passed"), javaOnly.stdout());
            assertFalse(Files.exists(testClass), "the removed Kotlin test class must be cleaned");
            assertTrue(Files.isRegularFile(projectDirectory.resolve(
                    "target/test-classes/com/example/MixedMainJavaTest.class")));
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static CommandResult testFromSeededCache(Path projectDirectory, Path cacheRoot) {
        return execute(
                "test",
                "--no-build-cache",
                "--cwd", projectDirectory.toString(),
                "--cache-root", cacheRoot.toString());
    }

    private static void writeProject(Path projectDirectory, CliTestRepository repository)
            throws IOException {
        Files.createDirectories(projectDirectory.resolve("src/main/java/com/example"));
        Files.createDirectories(projectDirectory.resolve("src/test/kotlin/com/example"));
        Files.writeString(projectDirectory.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-cli"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

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
                currentJavaMajorVersion(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
        Files.writeString(projectDirectory.resolve("src/main/java/com/example/Main.java"), """
                package com.example;

                public final class Main {
                    private Main() {}

                    public static String message() {
                        return "hello";
                    }
                }
                """);
        writeKotlinTest(projectDirectory, "hello", "first");
    }

    private static void writeKotlinMainProject(Path projectDirectory, CliTestRepository repository) throws IOException {
        Files.createDirectories(projectDirectory.resolve("src/main/kotlin/com/example"));
        Files.createDirectories(projectDirectory.resolve("src/main/java/com/example"));
        Files.createDirectories(projectDirectory.resolve("src/test/kotlin/com/example"));
        Files.writeString(projectDirectory.resolve("zolt.toml"), """
                [project]
                name = "kotlin-main-test-cli"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [build]
                sources = ["src/main/kotlin", "src/main/java"]

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
        writeKotlinMain(projectDirectory, "String");
        Files.writeString(projectDirectory.resolve("src/main/java/com/example/JavaMain.java"), """
                package com.example;

                public final class JavaMain {
                    private JavaMain() {}

                    public static String message() {
                        return "hello";
                    }

                    public static String kotlinMessage() {
                        return KotlinApi.message();
                    }
                }
                """);
        Files.writeString(projectDirectory.resolve("src/test/kotlin/com/example/DemoTest.kt"), """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class DemoTest {
                    @Test
                    fun verifiesMixedMainAndKotlinFriendAccess() {
                        val value: TestValue = Main.message()
                        assertEquals("hello", value)
                        assertEquals("kotlin", JavaMain.kotlinMessage())
                    }
                }
                """);
    }

    private static void writeKotlinMain(Path projectDirectory, String alias) throws IOException {
        Files.writeString(projectDirectory.resolve("src/main/kotlin/com/example/Main.kt"), """
                package com.example

                typealias TestValue = %s

                internal object Main {
                    @JvmStatic
                    fun message(): String = JavaMain.message()
                }

                object KotlinApi {
                    @JvmStatic
                    fun message(): String = "kotlin"
                }
                """.formatted(alias));
    }

    private static void writeJavaTest(Path projectDirectory) throws IOException {
        Path source = projectDirectory.resolve("src/test/java/com/example/MixedMainJavaTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                final class MixedMainJavaTest {
                    @Test
                    void verifiesJavaAndKotlinMainApis() {
                        assertEquals("hello", JavaMain.message());
                        assertEquals("kotlin", KotlinApi.message());
                        assertEquals("kotlin", JavaMain.kotlinMessage());
                    }
                }
                """);
    }

    private static void seed(CliTestRepository repository, Path projectDirectory, Path onlineCache,
            Path offlineCache, boolean kotlinMain) throws IOException {
        KotlinCompilerCliFixture.publish(repository);
        JUnitConsoleCliFixture.publish(repository);
        if (kotlinMain) {
            writeKotlinMainProject(projectDirectory, repository);
        } else {
            writeProject(projectDirectory, repository);
        }
        CommandResult resolve = execute(
                "resolve",
                "--cwd", projectDirectory.toString(),
                "--cache-root", onlineCache.toString());
        assertEquals(0, resolve.exitCode(), resolve.stderr());
        assertTrue(resolve.stdout().contains("wrote " + projectDirectory.resolve("zolt.lock")));
        Files.move(onlineCache, offlineCache);
        repository.clearAuthorizations();
    }

    private static void writeKotlinTest(
            Path projectDirectory,
            String expected,
            String variant) throws IOException {
        Files.writeString(projectDirectory.resolve("src/test/kotlin/com/example/DemoTest.kt"), """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class DemoTest {
                    @Test
                    fun verifiesJavaMain() {
                        assertEquals("%s", Main.message(), "%s")
                    }
                }
                """.formatted(expected, variant));
    }

    private static Path kotlinModule(Path outputDirectory) throws IOException {
        Path metadataDirectory = outputDirectory.resolve("META-INF");
        try (Stream<Path> paths = Files.list(metadataDirectory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".kotlin_module"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Kotlin module metadata was not compiled"));
        }
    }

    private static void deleteCompilerJars(Path cacheRoot) throws IOException {
        String fileName = "kotlin-compiler-embeddable-"
                + KotlinCompilerCliFixture.KOTLIN_VERSION
                + ".jar";
        List<Path> compilerJars;
        try (Stream<Path> paths = Files.walk(cacheRoot)) {
            compilerJars = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .toList();
        }
        assertFalse(compilerJars.isEmpty(), "the seeded cache must contain the Kotlin compiler");
        for (Path compilerJar : compilerJars) {
            Files.delete(compilerJar);
        }
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
