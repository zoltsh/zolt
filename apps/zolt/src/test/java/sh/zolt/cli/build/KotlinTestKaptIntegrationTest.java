package sh.zolt.cli.build;

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

/** End-to-end proof that Kotlin and Java tests consume KAPT-generated Java offline. */
final class KotlinTestKaptIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolvesCompilesRunsAndReusesKaptGeneratedTestsOffline() throws Exception {
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path project = tempDir.resolve("project");
            Path onlineCache = tempDir.resolve("online-cache");
            Path artifactCache = tempDir.resolve("artifact-cache");
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            KaptProcessorCliFixture.publish(repository, tempDir.resolve("processor"));
            writeProject(project, repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), resolve.stderr());
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

            CommandResult first = test(project, artifactCache);

            assertEquals(0, first.exitCode(), first.stderr());
            assertSuccessfulTests(first, 2);
            Path testOutput = project.resolve("target/test-classes/com/example");
            assertTrue(Files.isRegularFile(testOutput.resolve("GeneratedTestMessage.class")));
            assertTrue(Files.isRegularFile(testOutput.resolve("KaptKotlinTest.class")));
            assertTrue(Files.isRegularFile(testOutput.resolve("KaptJavaTest.class")));
            assertTrue(Files.isRegularFile(project.resolve(
                    "target/generated/test-sources/annotations/com/example/GeneratedTestMessage.java")));
            assertTiming(first, "compile test sources", "\"testCompilationMode\":\"full\"");

            CommandResult warm = test(project, artifactCache);

            assertEquals(0, warm.exitCode(), warm.stderr());
            assertSuccessfulTests(warm, 2);
            assertTiming(warm, "compile test sources", "\"testCompilationMode\":\"skipped\"");
            assertEquals(Map.of(), repository.authorizations(), "offline commands must not contact the repository");
        }
    }

    private static CommandResult test(Path project, Path artifactCache) {
        return execute(
                "test",
                "--no-build-cache",
                "--no-progress",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString());
    }

    private static void writeProject(Path project, CliTestRepository repository) throws IOException {
        Files.createDirectories(project.resolve("src/main/kotlin/com/example"));
        Files.createDirectories(project.resolve("src/test/kotlin/com/example"));
        Files.createDirectories(project.resolve("src/test/java/com/example"));
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-kapt"
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

                [dependencies.test-processor]
                "%s:%s" = "%s"
                """.formatted(
                currentJavaMajorVersion(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION,
                KaptProcessorCliFixture.GROUP,
                KaptProcessorCliFixture.ARTIFACT,
                KaptProcessorCliFixture.VERSION));
        Files.writeString(project.resolve("src/main/kotlin/com/example/Main.kt"), """
                package com.example

                internal object Main {
                    fun value(): String = "main"
                }
                """);
        Files.writeString(project.resolve("src/test/kotlin/com/example/KaptKotlinTest.kt"), """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class KaptKotlinTest {
                    @Test
                    fun kotlinSeesGeneratedTestType() {
                        assertEquals("generated-test", generated())
                    }

                    companion object {
                        @JvmStatic
                        fun generated(): String = GeneratedTestMessage.value()
                    }
                }
                """);
        Files.writeString(project.resolve("src/test/java/com/example/KaptJavaTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                public final class KaptJavaTest {
                    @Test
                    void javaSeesKotlinAndGeneratedTestTypes() {
                        assertEquals("generated-test", KaptKotlinTest.generated());
                        assertEquals("generated-test", GeneratedTestMessage.value());
                    }
                }
                """);
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
        assertTrue(result.stdout().matches(
                "(?s).*\\b" + expected + " tests found\\b.*"), result.stdout());
        assertTrue(result.stdout().matches(
                "(?s).*\\b" + expected + " tests successful\\b.*"), result.stdout());
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
