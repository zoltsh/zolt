package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** Real-compiler qualification for bounded cross-language {@code -Werror}. */
final class KotlinWarningsAsErrorsIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void failsKotlinAndJavaWarningPhasesAfterTheFlagIsEnabled() throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            writeProject(project, repository, false, true);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();

            CommandResult permissive = build(project, cache);
            assertEquals(0, permissive.exitCode(), permissive.stderr());

            writeManifest(project, repository, true);
            CommandResult kotlinWarning = build(project, cache);
            assertNotEquals(0, kotlinWarning.exitCode(), combinedOutput(kotlinWarning));
            assertWarningsAsErrorsFailure(kotlinWarning, "deprecated");

            writeKotlinSources(project, false);
            CommandResult javaWarning = build(project, cache);
            assertNotEquals(0, javaWarning.exitCode(), combinedOutput(javaWarning));
            assertWarningsAsErrorsFailure(javaWarning, "removal");

            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "resolved warning-policy builds must not contact the repository");
        }
    }

    private static CommandResult build(Path project, Path cache) {
        return execute(
                "build",
                "--no-build-cache",
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
    }

    private static void assertWarningsAsErrorsFailure(
            CommandResult result,
            String diagnostic) {
        String output = combinedOutput(result);
        assertTrue(output.contains("warning"), output);
        assertTrue(output.contains(diagnostic), output);
        assertTrue(output.contains("-werror"), output);
    }

    private static String combinedOutput(CommandResult result) {
        return (result.stdout() + "\n" + result.stderr()).toLowerCase(Locale.ROOT);
    }

    private static void writeProject(
            Path project,
            CliTestRepository repository,
            boolean warningsAsErrors,
            boolean kotlinWarning) throws IOException {
        Files.createDirectories(project.resolve("src/main/kotlin/com/example"));
        Files.createDirectories(project.resolve("src/main/java/com/example"));
        writeManifest(project, repository, warningsAsErrors);
        writeKotlinSources(project, kotlinWarning);
        Files.writeString(project.resolve("src/main/java/com/example/LegacyJavaApi.java"), """
                package com.example;

                public final class LegacyJavaApi {
                    private LegacyJavaApi() {}

                    @Deprecated(forRemoval = true)
                    public static String value() {
                        return "java";
                    }
                }
                """);
        Files.writeString(project.resolve("src/main/java/com/example/JavaBridge.java"), """
                package com.example;

                public final class JavaBridge {
                    private JavaBridge() {}

                    public static String value() {
                        return LegacyJavaApi.value();
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean warningsAsErrors) throws IOException {
        String compilerSettings = warningsAsErrors
                ? "[compiler]\nargs = [\"-Werror\"]\n"
                : "";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-warnings-as-errors"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.ApplicationKt"

                [build]
                sources = ["src/main/kotlin", "src/main/java"]

                %s
                [toolchain.kotlin]
                version = "%s"

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                currentJavaMajorVersion(),
                compilerSettings,
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION));
    }

    private static void writeKotlinSources(Path project, boolean warning) throws IOException {
        Files.writeString(project.resolve("src/main/kotlin/com/example/LegacyKotlin.kt"), """
                package com.example

                @Deprecated("legacy Kotlin API")
                fun legacyKotlin(): String = "kotlin"
                """);
        String value = warning ? "legacyKotlin()" : "\"kotlin\"";
        Files.writeString(project.resolve("src/main/kotlin/com/example/Application.kt"), """
                package com.example

                fun main() {
                    println(%s + "-" + JavaBridge.value())
                }
                """.formatted(value));
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
