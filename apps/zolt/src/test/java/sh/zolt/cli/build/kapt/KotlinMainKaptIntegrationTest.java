package sh.zolt.cli.build.kapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KaptProcessorCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** End-to-end proof that a Kotlin application consumes KAPT-generated Java offline. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class KotlinMainKaptIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolvesBuildsRunsAndReusesKaptGeneratedMainOffline() throws Exception {
        assumeTrue(System.getenv("ZOLT_USER_HOME") == null, "test needs an isolated user.home fallback");
        String previousUserHome = System.getProperty("user.home");
        Path fakeUserHome = tempDir.resolve("fake-user-home");
        System.setProperty("user.home", fakeUserHome.toString());
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path project = tempDir.resolve("project");
            Path onlineCache = tempDir.resolve("online-cache");
            Path artifactCache = tempDir.resolve("artifact-cache");
            configureBuildCache(fakeUserHome);
            KotlinCompilerCliFixture.publish(repository);
            KaptProcessorCliFixture.publish(repository, tempDir.resolve("processor"));
            writeProject(project, repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            String lock = Files.readString(project.resolve("zolt.lock"));
            assertTrue(lock.contains("id = \"org.jetbrains.kotlin:kotlin-annotation-processing-embeddable\""));
            assertTrue(lock.contains("scope = \"tool-kotlin\""));
            Files.move(onlineCache, artifactCache);
            repository.clearAuthorizations();

            CommandResult offlineResolve = execute(
                    "resolve",
                    "--locked",
                    "--offline",
                    "--cwd", project.toString(),
                    "--cache-root", artifactCache.toString(),
                    "--no-progress");
            assertEquals(0, offlineResolve.exitCode(), offlineResolve.stderr());

            CommandResult first = build(project, artifactCache);

            assertEquals(0, first.exitCode(), first.stderr());
            Path output = project.resolve("target/classes/com/example");
            assertTrue(Files.isRegularFile(output.resolve("GeneratedTestMessage.class")));
            assertTrue(Files.isRegularFile(output.resolve("Main.class")));
            assertTrue(Files.isRegularFile(output.resolve("JavaGreeting.class")));
            assertTrue(Files.isRegularFile(project.resolve(
                    "target/generated/sources/annotations/com/example/GeneratedTestMessage.java")));
            assertTiming(first, "full");

            CommandResult warm = build(project, artifactCache);

            assertEquals(0, warm.exitCode(), warm.stderr());
            assertTiming(warm, "skipped");

            deleteTree(project.resolve("target"));
            CommandResult restored = build(project, artifactCache);

            assertEquals(0, restored.exitCode(), restored.stderr());
            assertTiming(restored, "restored");
            assertTrue(Files.isDirectory(project.resolve("target/generated/sources/annotations")));
            assertTrue(Files.isRegularFile(output.resolve("GeneratedTestMessage.class")));

            CommandResult restoredWarm = build(project, artifactCache);

            assertEquals(0, restoredWarm.exitCode(), restoredWarm.stderr());
            assertTiming(restoredWarm, "skipped");

            CommandResult run = execute(
                    "run",
                    "--cwd", project.toString(),
                    "--cache-root", artifactCache.toString());

            assertEquals(0, run.exitCode(), run.stderr());
            assertTrue(run.stdout().contains("generated-test:generated-test"), run.stdout());
            assertEquals(Map.of(), repository.authorizations(), "cache-only commands must not contact the repository");
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private static CommandResult build(Path project, Path artifactCache) {
        return execute(
                "build",
                "--offline",
                "--no-progress",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString());
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

    private static void writeProject(Path project, CliTestRepository repository) throws IOException {
        Files.createDirectories(project.resolve("src/main/kotlin/com/example"));
        Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-main-kapt"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [build]
                sources = ["src/main/kotlin", "src/main/java"]

                [toolchain.kotlin]
                version = "%s"

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"

                [dependencies.processor]
                "%s:%s" = "%s"
                """.formatted(
                currentJavaMajorVersion(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KaptProcessorCliFixture.GROUP,
                KaptProcessorCliFixture.ARTIFACT,
                KaptProcessorCliFixture.VERSION));
        Files.writeString(project.resolve("src/main/kotlin/com/example/Main.kt"), """
                package com.example

                object Main {
                    @JvmStatic
                    fun generated(): String = GeneratedTestMessage.value()

                    @JvmStatic
                    fun main(args: Array<String>) {
                        println(JavaGreeting.message())
                    }
                }
                """);
        Files.writeString(project.resolve("src/main/java/com/example/JavaGreeting.java"), """
                package com.example;

                public final class JavaGreeting {
                    private JavaGreeting() {}

                    public static String message() {
                        return Main.generated() + ":" + GeneratedTestMessage.value();
                    }
                }
                """);
    }

    private static void assertTiming(CommandResult result, String mode) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile main\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing compile timing in:\n" + result.stderr()));
        assertTrue(line.contains("\"mainCompilationMode\":\"" + mode + "\""), line);
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
