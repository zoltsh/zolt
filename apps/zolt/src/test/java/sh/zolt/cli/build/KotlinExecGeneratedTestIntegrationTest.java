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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** Canonical CLI/worker proof for a pinned exec tool that owns generated Kotlin tests. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class KotlinExecGeneratedTestIntegrationTest {
    private static final String TEMPLATE = "src/test/generator/GeneratedKotlinTest.kt.in";
    private static final String GENERATED_SOURCE =
            "target/generated/test-sources/kotlin/com/example/GeneratedKotlinTest.kt";

    @TempDir
    private Path tempDir;

    @Test
    void generatesCompilesRunsCachesAndInvalidatesKotlinTests() throws Exception {
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
            Path generatedSource = project.resolve(GENERATED_SOURCE);
            Path generatedClass = project.resolve(
                    "target/test-classes/com/example/GeneratedKotlinTest.class");
            assertSuccessful(first);
            assertTrue(Files.isRegularFile(generatedSource));
            assertTrue(Files.isRegularFile(generatedClass));
            byte[] initialSource = Files.readAllBytes(generatedSource);
            byte[] initialClass = Files.readAllBytes(generatedClass);
            FileTime initialClassTime = Files.getLastModifiedTime(generatedClass);

            CommandResult warm = test(project, artifactCache);
            assertSuccessful(warm);
            assertTiming(warm, "compile test sources", "\"testCompilationMode\":\"skipped\"");
            assertArrayEquals(initialClass, Files.readAllBytes(generatedClass));
            assertEquals(initialClassTime, Files.getLastModifiedTime(generatedClass));

            deleteTree(project.resolve("target"));
            CommandResult restored = test(project, artifactCache);
            assertSuccessful(restored);
            assertTiming(restored, "compile test sources", "\"testCompilationMode\":\"restored\"");
            assertArrayEquals(initialSource, Files.readAllBytes(generatedSource));
            assertArrayEquals(initialClass, Files.readAllBytes(generatedClass));

            writeTemplate(project, "after");
            CommandResult changed = test(project, artifactCache);
            assertSuccessful(changed);
            assertTiming(changed, "compile test sources", "\"testCompilationMode\":\"full\"");
            assertFalse(Arrays.equals(initialSource, Files.readAllBytes(generatedSource)));
            assertFalse(Arrays.equals(initialClass, Files.readAllBytes(generatedClass)));
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
        ExecSourceGeneratorCliFixture.publish(repository, project.resolve("fixture-work"));
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
        Files.createDirectories(project.resolve(TEMPLATE).getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "exec-generated-kotlin-test"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [generated.tools.kotlin-source-generator]
                kind = "jvm"
                coordinates = [{ coordinate = "%s", version = "%s" }]
                mainClass = "%s"

                [generated.test.generated]
                kind = "exec"
                language = "kotlin"
                tool = "kotlin-source-generator"
                args = ["%s", "com/example/GeneratedKotlinTest.kt"]
                inputs = ["%s"]
                output = "target/generated/test-sources/kotlin"
                produces = "test-sources"

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
                ExecSourceGeneratorCliFixture.COORDINATE,
                ExecSourceGeneratorCliFixture.VERSION,
                ExecSourceGeneratorCliFixture.MAIN_CLASS,
                TEMPLATE,
                TEMPLATE,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
        Files.writeString(project.resolve("src/main/java/com/example/Main.java"), """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "main";
                    }
                }
                """);
        writeTemplate(project, "before");
    }

    private static void writeTemplate(Path project, String revision) throws IOException {
        Files.writeString(project.resolve(TEMPLATE), """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class GeneratedKotlinTest {
                    @Test
                    fun runsFromExecGenerator() {
                        assertEquals("main-%s", Main.message() + "-" + revision())
                    }

                    private fun revision(): String = "%s"
                }
                """.formatted(revision, revision));
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
        assertTrue(result.stdout().matches("(?s).*\\b1 tests successful\\b.*"), result.stdout());
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
