package sh.zolt.cli.build.kotlin.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Packaged CLI proof for mixed Kotlin/Java JVM-preview applications. */
final class KotlinJvmPreviewApplicationIntegrationTest {
    private static final String MAIN_CLASS = "com.example.PreviewApplicationKt";
    private static final String EXPECTED_OUTPUT = "preview-main-one";

    @TempDir
    private Path tempDir;

    @Test
    void runsAndPackagesPreviewClassesOfflineWithOwnedJvmEnablement() throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            writeProject(project, repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), combined(resolve));
            repository.clearAuthorizations();
            repository.close();

            CommandResult run = execute(
                    "run",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString(),
                    "--",
                    "one");
            assertApplicationRun(run, "Ran " + MAIN_CLASS);
            assertPreviewClass(project.resolve(
                    "target/classes/com/example/PreviewApplicationKt.class"));
            assertPreviewClass(project.resolve(
                    "target/classes/com/example/JavaPreview.class"));

            CommandResult warmRun = execute(
                    "run",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString(),
                    "--",
                    "one");
            assertApplicationRun(warmRun, "Ran " + MAIN_CLASS);

            Path jar = project.resolve("target/kotlin-jvm-preview-application-0.1.0.jar");
            CommandResult thinPackage = execute(
                    "package",
                    "--no-build-cache",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, thinPackage.exitCode(), combined(thinPackage));
            assertTrue(Files.isRegularFile(jar));

            CommandResult runPackage = execute(
                    "run-package",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString(),
                    "--",
                    "one");
            assertApplicationRun(runPackage, "Ran packaged " + MAIN_CLASS);

            CommandResult uberPackage = execute(
                    "package",
                    "--mode", "uber-jar",
                    "--no-build-cache",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, uberPackage.exitCode(), combined(uberPackage));

            ProcessResult ordinaryJava = runJar(jar, false, "one");
            assertTrue(ordinaryJava.exitCode() != 0, ordinaryJava.output());
            assertTrue(
                    ordinaryJava.output().contains("Preview features are not enabled"),
                    ordinaryJava.output());

            ProcessResult previewJava = runJar(jar, true, "one");
            assertEquals(0, previewJava.exitCode(), previewJava.output());
            assertEquals(List.of(EXPECTED_OUTPUT), previewJava.output().lines().toList());
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static void assertApplicationRun(CommandResult result, String summary) {
        assertEquals(0, result.exitCode(), combined(result));
        assertTrue(result.stdout().contains(EXPECTED_OUTPUT), result.stdout());
        assertTrue(result.stdout().contains(summary), result.stdout());
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }

    private static void assertPreviewClass(Path classFile) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        assertTrue(bytes.length >= 8);
        assertEquals(0xffff, unsignedShort(bytes, 4));
        assertEquals(Runtime.version().feature() + 44, unsignedShort(bytes, 6));
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 8 | bytes[offset + 1] & 0xff;
    }

    private static void writeProject(
            Path project,
            CliTestRepository repository) throws IOException {
        Path kotlin = project.resolve(
                "src/main/kotlin/com/example/PreviewApplication.kt");
        Path java = project.resolve("src/main/java/com/example/JavaPreview.java");
        Files.createDirectories(kotlin.getParent());
        Files.createDirectories(java.getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-jvm-preview-application"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "%s"

                [build]
                sources = ["src/main/kotlin", "src/main/java"]

                [toolchain.kotlin]
                version = "%s"

                [compiler]
                args = ["-parameters", "-Xjvm-enable-preview"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                Runtime.version().feature(),
                MAIN_CLASS,
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION));
        Files.writeString(kotlin, """
                package com.example

                fun main(args: Array<String>) {
                    println(JavaPreview.message(args.single()))
                }
                """);
        Files.writeString(java, """
                package com.example;

                import static java.lang.StringTemplate.STR;

                public final class JavaPreview {
                    private JavaPreview() {}

                    public static String message(String value) {
                        return STR."preview-main-\\{value}";
                    }
                }
                """);
    }

    private static ProcessResult runJar(
            Path jar,
            boolean preview,
            String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable("java")).toString());
        if (preview) {
            command.add("--enable-preview");
        }
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Timed out running preview uber JAR " + jar);
        }
        return new ProcessResult(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
