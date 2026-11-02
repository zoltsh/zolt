package sh.zolt.cli.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Canonical CLI/worker and output-cleanup proof for Kotlin test lambda modes. */
final class KotlinTestLambdaModeIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void replacesSyntheticTestLambdaClassesWhenModeChanges() throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeSource(project);
            writeManifest(project, repository, "class");

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();
            repository.close();

            CommandResult classMode = test(project, cache);
            assertSuccessful(classMode);
            assertTiming(classMode, "\"testCompilationMode\":\"full\"");
            assertFalse(lambdaClasses(project).isEmpty(), "Class mode emitted no lambda class");
            assertClassFileOmits(project, "java/lang/invoke/LambdaMetafactory");

            CommandResult classWarm = test(project, cache);
            assertSuccessful(classWarm);
            assertTiming(classWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, "indy");
            CommandResult indy = test(project, cache);
            assertSuccessful(indy);
            assertTiming(indy, "\"testCompilationMode\":\"full\"");
            assertClassFileContains(project, "java/lang/invoke/LambdaMetafactory");
            assertEquals(List.of(), lambdaClasses(project), "Indy rebuild left stale lambda classes");

            CommandResult indyWarm = test(project, cache);
            assertSuccessful(indyWarm);
            assertTiming(indyWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, "class");
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            assertFalse(lambdaClasses(project).isEmpty(), "Restored class mode emitted no lambda class");
            assertClassFileOmits(project, "java/lang/invoke/LambdaMetafactory");
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static CommandResult test(Path project, Path cache) {
        return execute(
                "test",
                "--no-build-cache",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
    }

    private static void assertSuccessful(CommandResult result) {
        assertEquals(0, result.exitCode(), combined(result));
        assertTrue(result.stdout().contains("Tests passed"), result.stdout());
        assertTrue(result.stdout().matches("(?s).*\\b1 tests successful\\b.*"), result.stdout());
    }

    private static void assertTiming(CommandResult result, String expected) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile test sources\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing compile test sources timing in:\n" + result.stderr()));
        assertTrue(line.contains(expected), line);
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }

    private static List<String> lambdaClasses(Path project) throws IOException {
        Path packageDirectory = project.resolve("target/test-classes/com/example");
        try (var paths = Files.list(packageDirectory)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("LambdaModeTest$") && name.endsWith(".class"))
                    .sorted()
                    .toList();
        }
    }

    private static void assertClassFileContains(Path project, String marker) throws IOException {
        assertTrue(classFileText(project).contains(marker),
                "Missing class-file marker `" + marker + "`");
    }

    private static void assertClassFileOmits(Path project, String marker) throws IOException {
        assertFalse(classFileText(project).contains(marker),
                "Unexpected class-file marker `" + marker + "`");
    }

    private static String classFileText(Path project) throws IOException {
        byte[] bytes = Files.readAllBytes(project.resolve(
                "target/test-classes/com/example/LambdaModeTest.class"));
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static void writeSource(Path project) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/LambdaModeTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class LambdaModeTest {
                    @Test
                    fun appliesLambda() {
                        assertEquals("lambda-Zolt", message("Zolt"))
                    }

                    private fun message(value: String): String {
                        val prefix = "lambda-"
                        val transform: (String) -> String = { input -> prefix + input }
                        return transform(value)
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            String mode) throws IOException {
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-lambda-mode"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [compiler.test]
                args = ["-parameters", "-Xlambdas=%s"]

                [test.sources]
                kotlin = ["src/test/kotlin"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies.test]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                "org.junit.platform:junit-platform-console-standalone" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                mode,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
    }
}
