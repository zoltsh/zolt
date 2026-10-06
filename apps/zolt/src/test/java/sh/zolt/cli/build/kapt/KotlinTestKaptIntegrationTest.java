package sh.zolt.cli.build.kapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KaptProcessorCliFixture;
import sh.zolt.cli.build.KotlinCliBuildCacheTestSupport;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** End-to-end proof that Kotlin and Java tests consume KAPT-generated Java offline. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class KotlinTestKaptIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolvesCompilesRunsAndReusesKaptGeneratedTestsOffline() throws Exception {
        assumeTrue(System.getenv("ZOLT_USER_HOME") == null, "test needs an isolated user.home fallback");
        String previousUserHome = System.getProperty("user.home");
        Path fakeUserHome = tempDir.resolve("fake-user-home");
        System.setProperty("user.home", fakeUserHome.toString());
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path project = tempDir.resolve("project");
            Path onlineCache = tempDir.resolve("online-cache");
            Path artifactCache = tempDir.resolve("artifact-cache");
            KotlinCliBuildCacheTestSupport.configure(fakeUserHome);
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

            CommandResult first = test(project, artifactCache, "configured-test");

            assertEquals(0, first.exitCode(), first.stderr());
            assertSuccessfulTests(first, 2);
            Path testOutput = project.resolve("target/test-classes/com/example");
            assertTrue(Files.isRegularFile(testOutput.resolve("GeneratedTestMessage.class")));
            assertTrue(Files.isRegularFile(testOutput.resolve("KaptKotlinTest.class")));
            assertTrue(Files.isRegularFile(testOutput.resolve("KaptJavaTest.class")));
            assertTrue(Files.isRegularFile(project.resolve(
                    "target/generated/test-sources/annotations/com/example/GeneratedTestMessage.java")));
            assertTiming(first, "compile test sources", "\"testCompilationMode\":\"full\"");

            CommandResult warm = test(project, artifactCache, "configured-test");

            assertEquals(0, warm.exitCode(), warm.stderr());
            assertSuccessfulTests(warm, 2);
            assertTiming(warm, "compile test sources", "\"testCompilationMode\":\"skipped\"");

            KotlinCliBuildCacheTestSupport.deleteTrees(project.resolve("target"));
            CommandResult restored = test(project, artifactCache, "configured-test");

            assertEquals(0, restored.exitCode(), restored.stderr());
            assertSuccessfulTests(restored, 2);
            assertTiming(restored, "compile test sources", "\"testCompilationMode\":\"restored\"");
            assertTrue(Files.isDirectory(project.resolve("target/generated/test-sources/annotations")));
            assertTrue(Files.isRegularFile(testOutput.resolve("GeneratedTestMessage.class")));

            CommandResult restoredWarm = test(project, artifactCache, "configured-test");

            assertEquals(0, restoredWarm.exitCode(), restoredWarm.stderr());
            assertSuccessfulTests(restoredWarm, 2);
            assertTiming(restoredWarm, "compile test sources", "\"testCompilationMode\":\"skipped\"");

            replace(project.resolve("zolt.toml"), "configured-test", "updated-test");
            CommandResult optionChanged = test(project, artifactCache, "updated-test");

            assertEquals(0, optionChanged.exitCode(), optionChanged.stderr());
            assertSuccessfulTests(optionChanged, 2);
            assertTiming(optionChanged, "compile test sources", "\"testCompilationMode\":\"full\"");

            CommandResult optionWarm = test(project, artifactCache, "updated-test");

            assertEquals(0, optionWarm.exitCode(), optionWarm.stderr());
            assertSuccessfulTests(optionWarm, 2);
            assertTiming(optionWarm, "compile test sources", "\"testCompilationMode\":\"skipped\"");
            assertEquals(Map.of(), repository.authorizations(), "offline commands must not contact the repository");
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private static CommandResult test(
            Path project,
            Path artifactCache,
            String expectedMessage) {
        return execute(
                "test",
                "--no-progress",
                "--timings",
                "--timings-format", "json",
                "--jvm-arg=-Dzolt.expected.message=" + expectedMessage,
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString());
    }

    private static void replace(Path path, String before, String after) throws IOException {
        Files.writeString(path, Files.readString(path).replace(before, after));
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

                [compiler.test]
                args = ["-Azolt.message=configured-test"]

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
                        assertEquals(System.getProperty("zolt.expected.message"), generated())
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
                        assertEquals(System.getProperty("zolt.expected.message"), KaptKotlinTest.generated());
                        assertEquals(System.getProperty("zolt.expected.message"), GeneratedTestMessage.value());
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
