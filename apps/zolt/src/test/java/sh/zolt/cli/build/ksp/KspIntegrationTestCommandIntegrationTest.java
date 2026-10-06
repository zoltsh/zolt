package sh.zolt.cli.build.ksp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Real CLI proof that the generated test lane also serves projected integration-test sources. */
final class KspIntegrationTestCommandIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void generatesExecutesAndReusesKspIntegrationTestsOffline() throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            KspCliFixture.publish(repository, tempDir.resolve("processor"));
            writeProject(project, repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult first = integrationTest(project, offlineCache);
            Path generated = project.resolve("target/generated/ksp/test/symbols");
            Path output = project.resolve("target/integration-test-classes");
            Path authoredClass = output.resolve("com/example/KspGeneratedIntegrationTest.class");

            assertEquals(0, first.exitCode(), first.stderr());
            assertTrue(first.stdout().contains("Integration tests passed"), first.stdout());
            assertTrue(first.stdout().contains("3 test source files"), first.stdout());
            assertTiming(first, "full");
            assertTrue(Files.isRegularFile(
                    generated.resolve("kotlin/com/example/GeneratedKspMessage.kt")));
            assertTrue(Files.isRegularFile(
                    generated.resolve("java/com/example/GeneratedJavaMessage.java")));
            assertEquals(
                    "integration-ksp-resource\n",
                    Files.readString(generated.resolve("resources/META-INF/ksp-cli.txt")));
            assertEquals(
                    "integration-ksp-resource\n",
                    Files.readString(output.resolve("META-INF/ksp-cli.txt")));
            assertTrue(Files.isRegularFile(
                    output.resolve("com/example/GeneratedKspMessage.class")));
            assertTrue(Files.isRegularFile(
                    output.resolve("com/example/GeneratedJavaMessage.class")));
            FileTime authoredClassTime = Files.getLastModifiedTime(authoredClass);

            CommandResult warm = integrationTest(project, offlineCache);

            assertEquals(0, warm.exitCode(), warm.stderr());
            assertTrue(warm.stdout().contains("Integration tests passed"), warm.stdout());
            assertTiming(warm, "skipped");
            assertEquals(authoredClassTime, Files.getLastModifiedTime(authoredClass));
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static CommandResult integrationTest(Path project, Path cache) {
        return execute(
                "integration-test",
                "--no-build-cache",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", cache.toString(),
                "--no-progress");
    }

    private static void writeProject(Path project, CliTestRepository repository) throws IOException {
        Path mainSource = project.resolve("src/main/java/com/example/Application.java");
        Path testSource = project.resolve(
                "src/integration-test/kotlin/com/example/KspGeneratedIntegrationTest.kt");
        Files.createDirectories(mainSource.getParent());
        Files.createDirectories(testSource.getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "ksp-integration-test-cli"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [test.integration]
                sources = ["src/integration-test/kotlin"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"

                [dependencies.test]
                "org.junit.platform:junit-platform-console-standalone" = "%s"

                [generated.tools.ksp]
                version = "%s"
                coordinates = [
                    { coordinate = "%s:%s", version = "%s" },
                ]

                [generated.test.symbols]
                kind = "ksp"
                options = { "fixture.message" = "integration-ksp", "fixture.requireSymbols" = "com.example.Application,com.example.KspGeneratedIntegrationTest" }
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION,
                KspCliFixture.KSP_VERSION,
                KspCliFixture.PROCESSOR_GROUP,
                KspCliFixture.PROCESSOR_ARTIFACT,
                KspCliFixture.PROCESSOR_VERSION));
        Files.writeString(mainSource, """
                package com.example;

                public final class Application {
                    private Application() {}

                    public static String value() {
                        return "main-output";
                    }
                }
                """);
        Files.writeString(testSource, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class KspGeneratedIntegrationTest {
                    @Test
                    fun consumesMainAndEveryGeneratedLane() {
                        assertEquals("main-output", Application.value())
                        val generated = GeneratedKspMessage.value()
                        assertEquals(generated, GeneratedJavaMessage.value())
                        val resource = javaClass.classLoader
                            .getResourceAsStream("META-INF/ksp-cli.txt")!!
                            .bufferedReader()
                            .use { it.readText() }
                        assertEquals("${generated}-resource\\n", resource)
                    }
                }
                """);
    }

    private static void assertTiming(CommandResult result, String mode) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile integration-test sources\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing integration-test compile timing in:\n" + result.stderr()));
        assertTrue(line.contains("\"testCompilationMode\":\"" + mode + "\""), line);
    }
}
