package sh.zolt.cli.build.kotlin.compatibility.assertions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Real-compiler proof for Kotlin platform-type extension-receiver assertion suppression. */
@Isolated("mutates user.home so global build-cache configuration cannot affect invalidation")
final class KotlinReceiverAssertionsIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void suppressesExtensionReceiverAssertionsAndInvalidatesWarmOutputOffline() throws Exception {
        assumeTrue(System.getenv("ZOLT_USER_HOME") == null, "test needs an isolated user.home fallback");
        String previousUserHome = System.getProperty("user.home");
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        System.setProperty("user.home", tempDir.resolve("fake-user-home").toString());
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            URI repositoryUri = repository.baseUri();
            writeSources(project);
            writeManifest(project, repositoryUri, false);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), combined(resolve));
            repository.clearAuthorizations();
            repository.close();

            assertBuild(project, cache, "full");
            assertClassCheck(project, true);
            assertBuild(project, cache, "skipped");
            assertReceiverAssertionFailure(run(project, cache));

            writeManifest(project, repositoryUri, true);
            assertBuild(project, cache, "full");
            assertClassCheck(project, false);
            assertBuild(project, cache, "skipped");
            CommandResult suppressed = run(project, cache);
            assertEquals(0, suppressed.exitCode(), combined(suppressed));
            assertTrue(suppressed.stdout().contains("accepted-null-receiver"), suppressed.stdout());

            writeManifest(project, repositoryUri, false);
            assertBuild(project, cache, "full");
            assertClassCheck(project, true);
            assertReceiverAssertionFailure(run(project, cache));
            assertEquals(Map.of(), repository.authorizations());
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private static void assertBuild(Path project, Path cache, String mode) {
        CommandResult result = execute(
                "build",
                "--offline",
                "--no-build-cache",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
        assertEquals(0, result.exitCode(), combined(result));
        String timing = result.stderr().lines()
                .filter(line -> line.contains("\"phase\":\"compile main\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing compile timing in:\n" + result.stderr()));
        assertTrue(timing.contains("\"mainCompilationMode\":\"" + mode + "\""), timing);
    }

    private static CommandResult run(Path project, Path cache) {
        return execute(
                "run",
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
    }

    private static void assertReceiverAssertionFailure(CommandResult result) {
        assertTrue(result.exitCode() != 0, combined(result));
        assertTrue(combined(result).contains("NullPointerException"), combined(result));
        assertTrue(combined(result).contains("value(...) must not be null"), combined(result));
    }

    private static void assertClassCheck(Path project, boolean expected) throws IOException {
        String bytes = new String(Files.readAllBytes(project.resolve(
                "target/classes/com/example/ReceiverApi.class")), StandardCharsets.ISO_8859_1);
        assertEquals(expected, bytes.contains("checkNotNull"), "receiver assertion marker");
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }

    private static void writeSources(Path project) throws IOException {
        Path kotlin = project.resolve("src/main/kotlin/com/example/ReceiverApi.kt");
        Path javaApi = project.resolve("src/main/java/com/example/JavaPlatform.java");
        Path main = project.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(kotlin.getParent());
        Files.createDirectories(javaApi.getParent());
        Files.writeString(kotlin, """
                package com.example

                fun String.marker(): String = "accepted-null-receiver"

                object ReceiverApi {
                    @JvmStatic
                    fun value(): String = JavaPlatform.value().marker()
                }
                """);
        Files.writeString(javaApi, """
                package com.example;

                public final class JavaPlatform {
                    public static String value() {
                        return null;
                    }
                }
                """);
        Files.writeString(main, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println(ReceiverApi.value());
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            URI repository,
            boolean suppressAssertions) throws IOException {
        String compilerSettings = suppressAssertions
                ? """
                        [compiler]
                        args = ["-Xno-param-assertions", "-Xno-receiver-assertions"]
                        """
                : """
                        [compiler]
                        args = ["-Xno-param-assertions"]
                        """;
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-receiver-assertions"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [build]
                sources = ["src/main/kotlin", "src/main/java"]

                [toolchain.kotlin]
                version = "%s"

                %s

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                compilerSettings,
                repository,
                KotlinCompilerCliFixture.KOTLIN_VERSION));
    }
}
