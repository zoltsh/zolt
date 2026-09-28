package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** Real-compiler CLI canary for mixed Java/Kotlin integration tests. */
final class IntegrationTestCommandKotlinIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void compilesRunsAndReusesMixedKotlinIntegrationTestsOffline() throws Exception {
        Path project = tempDir.resolve("kotlin-integration-project");
        Path cache = tempDir.resolve("artifact-cache");

        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeProject(project, repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();

            CommandResult first = integrationTest(project, cache);
            Path mainOutput = project.resolve("target/classes");
            Path integrationOutput = project.resolve("target/integration-test-classes");
            Path kotlinTestClass = integrationOutput.resolve(
                    "com/example/KotlinIntegrationTest.class");
            Path javaTestClass = integrationOutput.resolve(
                    "com/example/JavaIntegrationTest.class");
            Path resource = integrationOutput.resolve("integration.properties");

            assertEquals(0, first.exitCode(), first.stderr());
            assertTrue(first.stdout().contains("Integration tests passed"), first.stdout());
            assertCountPhrase(first.stdout(), 2, "test source files");
            assertCountPhrase(first.stdout(), 2, "tests found");
            assertCountPhrase(first.stdout(), 2, "tests successful");
            assertTrue(Files.isRegularFile(kotlinTestClass));
            assertTrue(Files.isRegularFile(javaTestClass));
            assertEquals("mode=integration\n", Files.readString(resource));

            Path mainModule = kotlinModule(mainOutput);
            Path integrationModule = kotlinModule(integrationOutput);
            assertNotEquals(mainModule.getFileName(), integrationModule.getFileName());
            byte[] kotlinClassBytes = Files.readAllBytes(kotlinTestClass);
            byte[] javaClassBytes = Files.readAllBytes(javaTestClass);
            byte[] moduleBytes = Files.readAllBytes(integrationModule);
            FileTime kotlinClassTime = Files.getLastModifiedTime(kotlinTestClass);
            FileTime javaClassTime = Files.getLastModifiedTime(javaTestClass);
            FileTime moduleTime = Files.getLastModifiedTime(integrationModule);

            CommandResult warm = integrationTest(project, cache);

            assertEquals(0, warm.exitCode(), warm.stderr());
            assertTrue(warm.stdout().contains("Integration tests passed"), warm.stdout());
            assertCountPhrase(warm.stdout(), 2, "tests successful");
            assertTiming(warm, "build integration-test inputs", "\"mainCompilationMode\":\"skipped\"");
            assertTiming(warm, "compile integration-test sources", "\"testCompilationMode\":\"skipped\"");
            assertArrayEquals(kotlinClassBytes, Files.readAllBytes(kotlinTestClass));
            assertArrayEquals(javaClassBytes, Files.readAllBytes(javaTestClass));
            assertArrayEquals(moduleBytes, Files.readAllBytes(integrationModule));
            assertEquals(kotlinClassTime, Files.getLastModifiedTime(kotlinTestClass));
            assertEquals(javaClassTime, Files.getLastModifiedTime(javaTestClass));
            assertEquals(moduleTime, Files.getLastModifiedTime(integrationModule));
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "integration-test commands after resolve must not contact the repository");
        }
    }

    private static void writeProject(Path project, CliTestRepository repository) throws IOException {
        Files.createDirectories(project.resolve("src/main/kotlin/com/example"));
        Files.createDirectories(project.resolve("src/integration-test/java/com/example"));
        Files.createDirectories(project.resolve("src/integration-test/kotlin/com/example"));
        Files.createDirectories(project.resolve("src/integration-test/resources"));
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-integration-cli"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "%s"

                [test.integration]
                sources = ["src/integration-test/java", "src/integration-test/kotlin"]
                resources = ["src/integration-test/resources"]

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
        Files.writeString(project.resolve("src/main/kotlin/com/example/InternalMain.kt"), """
                package com.example

                internal object InternalMain {
                    fun message(): String = "main-internal"
                }
                """);
        Files.writeString(
                project.resolve("src/integration-test/kotlin/com/example/KotlinIntegrationTest.kt"),
                """
                package com.example

                import java.util.Properties
                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class KotlinIntegrationTest {
                    @Test
                    fun seesJavaHalfOfCycleAndIntegrationResource() {
                        assertEquals("main-internal", JavaIntegrationTest.javaMessage())
                        val properties = Properties()
                        javaClass.getResourceAsStream("/integration.properties").use { input ->
                            requireNotNull(input) { "integration resource is missing" }
                            properties.load(input)
                        }
                        assertEquals("integration", properties.getProperty("mode"))
                    }

                    companion object {
                        @JvmStatic
                        fun kotlinMessage(): String = InternalMain.message()
                    }
                }
                """);
        Files.writeString(
                project.resolve("src/integration-test/java/com/example/JavaIntegrationTest.java"),
                """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                public final class JavaIntegrationTest {
                    @Test
                    void seesKotlinHalfOfCycle() {
                        assertEquals("main-internal", KotlinIntegrationTest.kotlinMessage());
                    }

                    static String javaMessage() {
                        return KotlinIntegrationTest.kotlinMessage();
                    }
                }
                """);
        Files.writeString(
                project.resolve("src/integration-test/resources/integration.properties"),
                "mode=integration\n");
    }

    private static CommandResult integrationTest(Path project, Path cache) {
        return execute(
                "integration-test",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
    }

    private static Path kotlinModule(Path output) throws IOException {
        try (Stream<Path> paths = Files.walk(output.resolve("META-INF"))) {
            var modules = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".kotlin_module"))
                    .toList();
            assertEquals(1, modules.size(), "Kotlin output must contain exactly one module metadata file");
            return modules.getFirst();
        }
    }

    private static void assertTiming(CommandResult result, String phase, String expected) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"" + phase + "\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing timing phase " + phase + " in:\n" + result.stderr()));
        assertTrue(line.contains(expected), line);
    }

    private static void assertCountPhrase(String output, int expected, String phrase) {
        assertTrue(
                output.matches("(?s).*\\b" + expected + " " + phrase + "\\b.*"),
                output);
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
