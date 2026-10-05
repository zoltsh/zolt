package sh.zolt.cli.build.kotlin.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Canonical CLI/worker proof for invokedynamic generation of annotated Kotlin lambdas. */
final class KotlinTestAnnotatedIndyLambdasIntegrationTest {
    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";
    private static final String MARKER_DESCRIPTOR = "com/example/Marker";

    @TempDir
    private Path tempDir;

    @Test
    void emitsAnnotatedTestLambdaAsIndyOfflineAndCleansFallbackClass()
            throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeSource(project);
            writeManifest(project, repository, false);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();
            repository.close();

            CommandResult fallback = test(project, cache);
            assertSuccessful(fallback);
            assertTiming(fallback, "\"testCompilationMode\":\"full\"");
            assertFalse(apiClassText(project).contains(LAMBDA_METAFACTORY));
            assertTrue(Files.isRegularFile(lambdaClass(project)));
            assertTrue(lambdaClassText(project).contains(MARKER_DESCRIPTOR));

            CommandResult fallbackWarm = test(project, cache);
            assertSuccessful(fallbackWarm);
            assertTiming(fallbackWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, true);
            CommandResult indy = test(project, cache);
            assertSuccessful(indy);
            assertTiming(indy, "\"testCompilationMode\":\"full\"");
            assertTrue(apiClassText(project).contains(LAMBDA_METAFACTORY));
            assertTrue(apiClassText(project).contains(MARKER_DESCRIPTOR));
            assertFalse(Files.exists(lambdaClass(project)));

            CommandResult indyWarm = test(project, cache);
            assertSuccessful(indyWarm);
            assertTiming(indyWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, false);
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            assertFalse(apiClassText(project).contains(LAMBDA_METAFACTORY));
            assertTrue(Files.isRegularFile(lambdaClass(project)));
            assertTrue(lambdaClassText(project).contains(MARKER_DESCRIPTOR));
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

    private static Path lambdaClass(Path project) {
        return project.resolve(
                "target/test-classes/com/example/AnnotatedLambdaTestApi$action$1.class");
    }

    private static String apiClassText(Path project) throws IOException {
        return classFileText(project.resolve(
                "target/test-classes/com/example/AnnotatedLambdaTestApi.class"));
    }

    private static String lambdaClassText(Path project) throws IOException {
        return classFileText(lambdaClass(project));
    }

    private static String classFileText(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
    }

    private static void writeSource(Path project) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/AnnotatedIndyLambdaTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                @Target(AnnotationTarget.FUNCTION)
                @Retention(AnnotationRetention.RUNTIME)
                annotation class Marker

                object AnnotatedLambdaTestApi {
                    fun action(): () -> String = @Marker { "annotated" }
                }

                class AnnotatedIndyLambdaTest {
                    @Test
                    fun preservesRuntimeBehavior() {
                        assertEquals("annotated", AnnotatedLambdaTestApi.action()())
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean annotatedIndy) throws IOException {
        String compilerArguments = annotatedIndy
                ? "\"-parameters\", \"-Xlambdas=indy\", \"-Xindy-allow-annotated-lambdas\""
                : "\"-parameters\", \"-Xlambdas=indy\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-annotated-indy-lambdas"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [compiler.test]
                args = [%s]

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
                compilerArguments,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
    }
}
