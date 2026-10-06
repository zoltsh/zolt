package sh.zolt.cli.build.kapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KaptProcessorCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** End-to-end proof that a Kotlin application consumes KAPT-generated Java offline. */
final class KotlinMainKaptIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolvesBuildsRunsAndReusesKaptGeneratedMainOffline() throws Exception {
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path project = tempDir.resolve("project");
            Path onlineCache = tempDir.resolve("online-cache");
            Path artifactCache = tempDir.resolve("artifact-cache");
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

            CommandResult run = execute(
                    "run",
                    "--cwd", project.toString(),
                    "--cache-root", artifactCache.toString());

            assertEquals(0, run.exitCode(), run.stderr());
            assertTrue(run.stdout().contains("generated-test:generated-test"), run.stdout());
            assertEquals(Map.of(), repository.authorizations(), "cache-only commands must not contact the repository");
        }
    }

    private static CommandResult build(Path project, Path artifactCache) {
        return execute(
                "build",
                "--offline",
                "--no-build-cache",
                "--no-progress",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString());
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
