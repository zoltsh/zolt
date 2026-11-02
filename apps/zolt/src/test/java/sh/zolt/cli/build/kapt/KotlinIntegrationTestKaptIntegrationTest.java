package sh.zolt.cli.build.kapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;

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

/** End-to-end proof that Kotlin and Java integration tests consume KAPT-generated Java offline. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class KotlinIntegrationTestKaptIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void compilesRunsRestoresAndInvalidatesKaptGeneratedIntegrationTestsOffline() throws Exception {
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
            assertEquals(0, resolve.exitCode(), combined(resolve));
            Files.move(onlineCache, artifactCache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult first = integrationTest(project, artifactCache, "configured-integration");
            assertSuccessful(first);
            assertTiming(first, "full");
            Path output = project.resolve("target/integration-test-classes/com/example");
            Path generatedSources = project.resolve("target/generated/test-sources/annotations");
            Path generatedSource = generatedSources.resolve("com/example/GeneratedTestMessage.java");
            assertTrue(Files.isRegularFile(output.resolve("GeneratedTestMessage.class")));
            assertTrue(Files.isRegularFile(output.resolve("KaptIntegrationTest.class")));
            assertTrue(Files.isRegularFile(output.resolve("KaptJavaIntegrationTest.class")));
            assertTrue(Files.readString(generatedSource).contains("configured-integration"));

            CommandResult warm = integrationTest(project, artifactCache, "configured-integration");
            assertSuccessful(warm);
            assertTiming(warm, "skipped");

            KotlinCliBuildCacheTestSupport.deleteTrees(
                    project.resolve("target/integration-test-classes"),
                    generatedSources);
            CommandResult restored = integrationTest(project, artifactCache, "configured-integration");
            assertSuccessful(restored);
            assertTiming(restored, "restored");
            assertTrue(Files.isDirectory(generatedSources));
            assertTrue(Files.isRegularFile(output.resolve("GeneratedTestMessage.class")));

            CommandResult restoredWarm = integrationTest(project, artifactCache, "configured-integration");
            assertSuccessful(restoredWarm);
            assertTiming(restoredWarm, "skipped");

            replace(project.resolve("zolt.toml"), "configured-integration", "updated-integration");
            CommandResult updated = integrationTest(project, artifactCache, "updated-integration");
            assertSuccessful(updated);
            assertTiming(updated, "full");
            assertTrue(Files.readString(generatedSource).contains("updated-integration"));

            CommandResult updatedWarm = integrationTest(project, artifactCache, "updated-integration");
            assertSuccessful(updatedWarm);
            assertTiming(updatedWarm, "skipped");
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "integration-test KAPT commands must remain cache-only after resolve");
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private static CommandResult integrationTest(Path project, Path cache, String expectedMessage) {
        return execute(
                "integration-test",
                "--no-progress",
                "--timings",
                "--timings-format", "json",
                "--jvm-arg=-Dzolt.expected.message=" + expectedMessage,
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
    }

    private static void assertSuccessful(CommandResult result) {
        assertEquals(0, result.exitCode(), combined(result));
        assertTrue(result.stdout().contains("Integration tests passed"), result.stdout());
        assertTrue(result.stdout().matches("(?s).*\\b2 tests found\\b.*"), result.stdout());
        assertTrue(result.stdout().matches("(?s).*\\b2 tests successful\\b.*"), result.stdout());
    }

    private static void assertTiming(CommandResult result, String mode) {
        String timing = result.stderr().lines()
                .filter(line -> line.contains("\"phase\":\"compile integration-test sources\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing integration-test compile timing in:\n" + result.stderr()));
        assertTrue(timing.contains("\"testCompilationMode\":\"" + mode + "\""), timing);
    }

    private static void writeProject(Path project, CliTestRepository repository) throws Exception {
        Path mainSource = project.resolve("src/main/kotlin/com/example/Main.kt");
        Path kotlinTest = project.resolve("src/integration-test/kotlin/com/example/KaptIntegrationTest.kt");
        Path javaTest = project.resolve(
                "src/integration-test/java/com/example/KaptJavaIntegrationTest.java");
        Files.createDirectories(mainSource.getParent());
        Files.createDirectories(kotlinTest.getParent());
        Files.createDirectories(javaTest.getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-integration-kapt"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [build]
                sources = ["src/main/kotlin"]

                [compiler.test]
                args = ["-Azolt.message=configured-integration"]

                [toolchain.kotlin]
                version = "%s"

                [test.integration]
                sources = ["src/integration-test/kotlin", "src/integration-test/java"]

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
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION,
                KaptProcessorCliFixture.GROUP,
                KaptProcessorCliFixture.ARTIFACT,
                KaptProcessorCliFixture.VERSION));
        Files.writeString(mainSource, """
                package com.example

                internal object Main {
                    fun value(): String = "main"
                }
                """);
        Files.writeString(kotlinTest, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class KaptIntegrationTest {
                    @Test
                    fun kotlinSeesMainAndGeneratedTypes() {
                        assertEquals("main", Main.value())
                        assertEquals(System.getProperty("zolt.expected.message"), generated())
                    }

                    companion object {
                        @JvmStatic
                        fun generated(): String = GeneratedTestMessage.value()
                    }
                }
                """);
        Files.writeString(javaTest, """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                public final class KaptJavaIntegrationTest {
                    @Test
                    void javaSeesKotlinAndGeneratedTypes() {
                        assertEquals(System.getProperty("zolt.expected.message"), KaptIntegrationTest.generated());
                        assertEquals(System.getProperty("zolt.expected.message"), GeneratedTestMessage.value());
                    }
                }
                """);
    }

    private static void replace(Path path, String before, String after) throws Exception {
        Files.writeString(path, Files.readString(path).replace(before, after));
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }
}
