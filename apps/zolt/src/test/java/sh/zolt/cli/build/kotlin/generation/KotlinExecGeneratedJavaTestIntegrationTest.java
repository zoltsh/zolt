package sh.zolt.cli.build.kotlin.generation;

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
import sh.zolt.cli.build.ExecSourceGeneratorCliFixture;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** CLI proof for generated Java tests owned by a Kotlin-bearing test source set. */
final class KotlinExecGeneratedJavaTestIntegrationTest {
    private static final String TEMPLATE = "src/test/generator/GeneratedTestSupport.java.in";
    private static final String GENERATED_SOURCE =
            "target/generated/test-sources/java/com/example/GeneratedTestSupport.java";

    @TempDir
    private Path tempDir;

    @Test
    void generatesRepairsAndInvalidatesJavaConsumedByKotlinTests() throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path artifactCache = tempDir.resolve("artifact-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            ExecSourceGeneratorCliFixture.publish(repository, tempDir.resolve("fixture-work"));
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
            assertSuccessful(first);
            Path generatedSource = project.resolve(GENERATED_SOURCE);
            Path generatedClass = project.resolve(
                    "target/test-classes/com/example/GeneratedTestSupport.class");
            assertTrue(Files.isRegularFile(generatedSource));
            assertTrue(Files.isRegularFile(generatedClass));
            assertTrue(Files.isRegularFile(project.resolve(
                    "target/test-classes/com/example/GeneratedJavaConsumerTest.class")));
            assertTiming(first, "full");

            CommandResult warm = test(project, artifactCache);
            assertSuccessful(warm);
            assertTiming(warm, "skipped");

            Files.delete(generatedSource);
            CommandResult regenerated = test(project, artifactCache);
            assertSuccessful(regenerated);
            assertTrue(Files.isRegularFile(generatedSource));
            assertTiming(regenerated, "skipped");

            writeTemplate(project, "int", "1");
            CommandResult incompatible = test(project, artifactCache);
            assertEquals(1, incompatible.exitCode());
            assertTrue(
                    incompatible.stderr().contains("String")
                            && incompatible.stderr().contains("Int"),
                    incompatible.stderr());

            writeTemplate(project, "String", "\"after\"");
            CommandResult repaired = test(project, artifactCache);
            assertSuccessful(repaired);
            assertTiming(repaired, "full");
            assertEquals(Map.of(), repository.authorizations());
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
        Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.createDirectories(project.resolve("src/test/kotlin/com/example"));
        Files.createDirectories(project.resolve(TEMPLATE).getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "exec-generated-java-test"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [test.sources]
                kotlin = ["src/test/kotlin"]

                [generated.tools.java-source-generator]
                kind = "jvm"
                coordinates = [{ coordinate = "%s", version = "%s" }]
                mainClass = "%s"

                [generated.test.generated]
                kind = "exec"
                tool = "java-source-generator"
                args = ["%s", "com/example/GeneratedTestSupport.java"]
                inputs = ["%s"]
                output = "target/generated/test-sources/java"
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
                    private Main() {}

                    public static String message() {
                        return "main";
                    }
                }
                """);
        Files.writeString(project.resolve(
                "src/test/kotlin/com/example/GeneratedJavaConsumerTest.kt"), """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class GeneratedJavaConsumerTest {
                    @Test
                    fun consumesGeneratedJava() {
                        val generated: String = GeneratedTestSupport.value()
                        assertEquals("main-after", Main.message() + "-" + generated)
                    }
                }
                """);
        writeTemplate(project, "String", "\"after\"");
    }

    private static void writeTemplate(Path project, String returnType, String value)
            throws IOException {
        Files.writeString(project.resolve(TEMPLATE), """
                package com.example;

                public final class GeneratedTestSupport {
                    private GeneratedTestSupport() {}

                    public static %s value() {
                        return %s;
                    }
                }
                """.formatted(returnType, value));
    }

    private static void assertSuccessful(CommandResult result) {
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().matches("(?s).*\\b1 tests successful\\b.*"), result.stdout());
    }

    private static void assertTiming(CommandResult result, String mode) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile test sources\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing test compilation timing in:\n" + result.stderr()));
        assertTrue(line.contains("\"testCompilationMode\":\"" + mode + "\""), line);
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
