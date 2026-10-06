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

/** Real CLI proof for KSP2-generated Kotlin, Java, and resources in the unit-test source set. */
final class KspTestCommandIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolvesCompilesExecutesAndReusesKspTestsOffline() throws Exception {
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
            String lock = Files.readString(project.resolve("zolt.lock"));
            assertTrue(lock.contains("toolGroups = [\"ksp:ksp:engine\"]"));
            assertTrue(lock.contains("toolGroups = [\"ksp:ksp:processors\"]"));
            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();

            CommandResult offlineResolve = execute(
                    "resolve",
                    "--locked",
                    "--offline",
                    "--cwd", project.toString(),
                    "--cache-root", offlineCache.toString(),
                    "--no-progress");
            assertEquals(0, offlineResolve.exitCode(), offlineResolve.stderr());

            CommandResult first = test(project, offlineCache);
            Path generated = project.resolve("target/generated/ksp/test/symbols");
            Path testOutput = project.resolve("target/test-classes");
            Path authoredTestClass = testOutput.resolve("com/example/KspGeneratedTest.class");

            assertEquals(0, first.exitCode(), first.stderr());
            assertTrue(first.stdout().contains("Tests passed"), first.stdout());
            assertTrue(first.stdout().contains("3 test source files"), first.stdout());
            assertTiming(first, "full");
            assertTrue(Files.isRegularFile(
                    generated.resolve("kotlin/com/example/GeneratedKspMessage.kt")));
            assertTrue(Files.isRegularFile(
                    generated.resolve("java/com/example/GeneratedJavaMessage.java")));
            assertEquals(
                    "test-ksp-resource\n",
                    Files.readString(generated.resolve("resources/META-INF/ksp-cli.txt")));
            assertEquals(
                    "test-ksp-resource\n",
                    Files.readString(testOutput.resolve("META-INF/ksp-cli.txt")));
            assertTrue(Files.isRegularFile(
                    testOutput.resolve("com/example/GeneratedKspMessage.class")));
            assertTrue(Files.isRegularFile(
                    testOutput.resolve("com/example/GeneratedJavaMessage.class")));
            assertTrue(Files.isRegularFile(authoredTestClass));
            FileTime authoredClassTime = Files.getLastModifiedTime(authoredTestClass);

            CommandResult warm = test(project, offlineCache);

            assertEquals(0, warm.exitCode(), warm.stderr());
            assertTrue(warm.stdout().contains("Tests passed"), warm.stdout());
            assertTiming(warm, "skipped");
            assertEquals(authoredClassTime, Files.getLastModifiedTime(authoredTestClass));

            Path manifest = project.resolve("zolt.toml");
            Files.writeString(
                    manifest,
                    Files.readString(manifest).replace(
                            "\"fixture.message\" = \"test-ksp\"",
                            "\"fixture.message\" = \"changed-ksp\""));
            CommandResult refreshed = execute(
                    "resolve",
                    "--offline",
                    "--cwd", project.toString(),
                    "--cache-root", offlineCache.toString(),
                    "--no-progress");
            assertEquals(0, refreshed.exitCode(), refreshed.stderr());
            CommandResult changed = test(project, offlineCache);

            assertEquals(0, changed.exitCode(), changed.stderr());
            assertTrue(changed.stdout().contains("Tests passed"), changed.stdout());
            assertTiming(changed, "full");
            assertTrue(Files.readString(
                    generated.resolve("kotlin/com/example/GeneratedKspMessage.kt"))
                    .contains("\"changed-ksp\""));
            assertEquals(
                    "changed-ksp-resource\n",
                    Files.readString(testOutput.resolve("META-INF/ksp-cli.txt")));

            CommandResult clean = execute(
                    "clean",
                    "--cwd", project.toString(),
                    "--no-progress");

            assertEquals(0, clean.exitCode(), clean.stderr());
            assertTrue(Files.notExists(project.resolve("target")));
            assertTrue(Files.isRegularFile(project.resolve("zolt.toml")));
            assertTrue(Files.isRegularFile(project.resolve("zolt.lock")));
            assertTrue(Files.isRegularFile(
                    project.resolve("src/test/kotlin/com/example/KspGeneratedTest.kt")));

            CommandResult rebuilt = test(project, offlineCache);

            assertEquals(0, rebuilt.exitCode(), rebuilt.stderr());
            assertTrue(rebuilt.stdout().contains("Tests passed"), rebuilt.stdout());
            assertTiming(rebuilt, "full");
            assertTrue(Files.isRegularFile(
                    generated.resolve("kotlin/com/example/GeneratedKspMessage.kt")));
            assertTrue(Files.isRegularFile(
                    generated.resolve("java/com/example/GeneratedJavaMessage.java")));
            assertEquals(
                    "changed-ksp-resource\n",
                    Files.readString(testOutput.resolve("META-INF/ksp-cli.txt")));
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "locked cache-only KSP test commands must not contact the repository");
        }
    }

    private static CommandResult test(Path project, Path cache) {
        return execute(
                "test",
                "--no-build-cache",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", cache.toString(),
                "--no-progress");
    }

    private static void writeProject(Path project, CliTestRepository repository) throws IOException {
        Path mainSource = project.resolve("src/main/java/com/example/Application.java");
        Path testSource = project.resolve("src/test/kotlin/com/example/KspGeneratedTest.kt");
        Files.createDirectories(mainSource.getParent());
        Files.createDirectories(testSource.getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "ksp-test-cli"
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
                options = { "fixture.message" = "test-ksp" }
                """.formatted(
                currentJavaMajorVersion(),
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
                }
                """);
        Files.writeString(testSource, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class KspGeneratedTest {
                    @Test
                    fun consumesEveryGeneratedLane() {
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
                .filter(value -> value.contains("\"phase\":\"compile test sources\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing test compile timing in:\n" + result.stderr()));
        assertTrue(line.contains("\"testCompilationMode\":\"" + mode + "\""), line);
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
