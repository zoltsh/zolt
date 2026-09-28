package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** Real compiler and cache lifecycle for declared Java roots beside Kotlin unit tests. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class KotlinDeclaredTestRootIntegrationTest {
    private static final String DECLARED_ROOT = "generated/test/java";

    @TempDir
    private Path tempDir;

    @Test
    void compilesCachesInvalidatesAndProtectsDeclaredJavaTests() throws Exception {
        assumeTrue(System.getenv("ZOLT_USER_HOME") == null, "test needs an isolated user.home fallback");
        String previousUserHome = System.getProperty("user.home");
        Path fakeUserHome = tempDir.resolve("fake-user-home");
        System.setProperty("user.home", fakeUserHome.toString());
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path project = tempDir.resolve("project");
            Path onlineCache = tempDir.resolve("online-cache");
            Path artifactCache = tempDir.resolve("artifact-cache");
            configureBuildCache(fakeUserHome);
            seed(repository, project, onlineCache, artifactCache);
            repository.close();

            CommandResult first = test(project, artifactCache);
            Path declaredSource = project.resolve(
                    DECLARED_ROOT + "/com/example/DeclaredJavaCycleTest.java");
            Path kotlinClass = project.resolve("target/test-classes/com/example/KotlinCycleTest.class");
            Path declaredClass = project.resolve(
                    "target/test-classes/com/example/DeclaredJavaCycleTest.class");
            assertSuccessful(first);
            assertTrue(Files.isRegularFile(declaredSource));
            assertTrue(Files.isRegularFile(kotlinClass));
            assertTrue(Files.isRegularFile(declaredClass));
            byte[] initialDeclaredSource = Files.readAllBytes(declaredSource);
            FileTime initialDeclaredSourceTime = Files.getLastModifiedTime(declaredSource);
            byte[] initialDeclaredClass = Files.readAllBytes(declaredClass);
            byte[] initialKotlinClass = Files.readAllBytes(kotlinClass);
            FileTime initialDeclaredTime = Files.getLastModifiedTime(declaredClass);

            CommandResult warm = test(project, artifactCache);
            assertSuccessful(warm);
            assertTiming(warm, "compile test sources", "\"testCompilationMode\":\"skipped\"");
            assertArrayEquals(initialDeclaredClass, Files.readAllBytes(declaredClass));
            assertEquals(initialDeclaredTime, Files.getLastModifiedTime(declaredClass));

            deleteTree(project.resolve("target"));
            CommandResult restored = test(project, artifactCache);
            assertSuccessful(restored);
            assertTiming(restored, "compile test sources", "\"testCompilationMode\":\"restored\"");
            assertArrayEquals(initialDeclaredClass, Files.readAllBytes(declaredClass));
            assertArrayEquals(initialKotlinClass, Files.readAllBytes(kotlinClass));
            assertArrayEquals(
                    initialDeclaredSource,
                    Files.readAllBytes(declaredSource),
                    "cache restore must preserve declared input");
            assertEquals(initialDeclaredSourceTime, Files.getLastModifiedTime(declaredSource));

            writeDeclaredJavaTest(project, "after");
            CommandResult changed = test(project, artifactCache);
            assertSuccessful(changed);
            assertTiming(changed, "compile test sources", "\"testCompilationMode\":\"full\"");
            assertFalse(java.util.Arrays.equals(initialDeclaredClass, Files.readAllBytes(declaredClass)));
            byte[] changedDeclaredClass = Files.readAllBytes(declaredClass);

            deleteTree(project.resolve(DECLARED_ROOT));
            CommandResult missing = test(project, artifactCache);
            assertEquals(1, missing.exitCode());
            assertTrue(missing.stderr().contains("Generated source root `" + DECLARED_ROOT + "` is missing"),
                    missing.stderr());
            assertArrayEquals(
                    changedDeclaredClass,
                    Files.readAllBytes(declaredClass),
                    "missing declared input must fail before owned output cleanup");

            writeDeclaredJavaTest(project, "repaired");
            CommandResult repaired = test(project, artifactCache);
            assertSuccessful(repaired);
            assertFalse(java.util.Arrays.equals(changedDeclaredClass, Files.readAllBytes(declaredClass)));
            assertEquals(Map.of(), repository.authorizations());
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
        Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.createDirectories(project.resolve("src/test/kotlin/com/example"));
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-declared-test-root"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [test.sources]
                kotlin = ["src/test/kotlin"]

                [generated.test.declared]
                kind = "declared-root"
                language = "java"
                inputs = ["declared-tests.marker"]
                output = "%s"
                required = true
                clean = false

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
                DECLARED_ROOT,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
        Files.writeString(project.resolve("declared-tests.marker"), "committed\n");
        Files.writeString(project.resolve("src/main/java/com/example/Main.java"), """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "main";
                    }
                }
                """);
        Files.writeString(project.resolve("src/test/kotlin/com/example/KotlinCycleTest.kt"), """
                package com.example

                import org.junit.jupiter.api.Assertions.assertFalse
                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class KotlinCycleTest {
                    @Test
                    fun seesDeclaredJava() {
                        assertFalse(DeclaredJavaCycleTest.javaMessage().isBlank())
                        assertEquals("main", Main.message())
                    }

                    companion object {
                        @JvmStatic
                        fun kotlinMessage(): String = "kotlin"
                    }
                }
                """);
        writeDeclaredJavaTest(project, "before");
    }

    private static void writeDeclaredJavaTest(Path project, String revision) throws IOException {
        Path source = project.resolve(DECLARED_ROOT + "/com/example/DeclaredJavaCycleTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                public final class DeclaredJavaCycleTest {
                    @Test
                    void seesKotlin() {
                        assertEquals("kotlin", KotlinCycleTest.kotlinMessage());
                    }

                    public static String javaMessage() {
                        return "%s";
                    }
                }
                """.formatted(revision));
    }

    private static void configureBuildCache(Path fakeUserHome) throws IOException {
        Path globalDirectory = fakeUserHome.resolve(".zolt");
        Files.createDirectories(globalDirectory);
        Files.writeString(globalDirectory.resolve("config.toml"), """
                version = 1

                [buildCache]
                enabled = true
                dir = "build-cache"
                """);
    }

    private static CommandResult test(Path project, Path artifactCache) {
        return execute(
                "test",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString());
    }

    private static void assertSuccessful(CommandResult result) {
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Tests passed"), result.stdout());
        assertTrue(result.stdout().matches("(?s).*\\b2 tests successful\\b.*"), result.stdout());
    }

    private static void assertTiming(CommandResult result, String phase, String expected) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"" + phase + "\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing timing phase " + phase + " in:\n" + result.stderr()));
        assertTrue(line.contains(expected), line);
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
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
