package sh.zolt.cli.build.kotlin.compatibility;

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
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** A workspace launcher inherits JVM-preview requirements from runtime dependency members. */
final class KotlinJvmPreviewWorkspaceDependencyIntegrationTest {
    private static final String APPLICATION = "apps/application";
    private static final String MAIN_CLASS = "com.example.application.ApplicationKt";

    @TempDir
    private Path tempDir;

    @Test
    void runsOrdinaryApplicationWithPreviewMarkedWorkspaceDependencyOffline() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            writeWorkspace(workspace, repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), combined(resolve));
            repository.clearAuthorizations();
            repository.close();

            CommandResult run = run(workspace, cache, "one");
            assertRun(run, "preview-library-one", "Ran " + MAIN_CLASS + " in " + APPLICATION);
            assertClassVersion(
                    workspace.resolve(
                            "modules/library/target/classes/com/example/library/PreviewLibrary.class"),
                    0xffff);
            assertClassVersion(
                    workspace.resolve(
                            "apps/application/target/classes/com/example/application/ApplicationKt.class"),
                    0);

            CommandResult warmRun = run(workspace, cache, "two");
            assertRun(
                    warmRun,
                    "preview-library-two",
                    "Ran " + MAIN_CLASS + " in " + APPLICATION);

            CommandResult runPackage = execute(
                    "run-package",
                    "--workspace",
                    "--member", APPLICATION,
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString(),
                    "--",
                    "three");
            assertRun(
                    runPackage,
                    "preview-library-three",
                    "Ran packaged " + MAIN_CLASS + " in " + APPLICATION);
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static CommandResult run(Path workspace, Path cache, String argument) {
        return execute(
                "run",
                "--workspace",
                "--member", APPLICATION,
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString(),
                "--",
                argument);
    }

    private static void assertRun(
            CommandResult result,
            String expectedOutput,
            String expectedSummary) {
        assertEquals(0, result.exitCode(), combined(result));
        assertTrue(result.stdout().contains(expectedOutput), result.stdout());
        assertTrue(result.stdout().contains(expectedSummary), result.stdout());
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }

    private static void assertClassVersion(Path classFile, int expectedMinor) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        assertTrue(bytes.length >= 8);
        assertEquals(expectedMinor, unsignedShort(bytes, 4));
        assertEquals(Runtime.version().feature() + 44, unsignedShort(bytes, 6));
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 8 | bytes[offset + 1] & 0xff;
    }

    private static void writeWorkspace(
            Path workspace,
            CliTestRepository repository) throws IOException {
        Path library = workspace.resolve("modules/library");
        Path application = workspace.resolve(APPLICATION);
        Files.createDirectories(library);
        Files.createDirectories(application);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "kotlin-jvm-preview-dependency"

                [workspace.members]
                include = ["modules/library", "apps/application"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"
                """.formatted(repository.baseUri()));
        writeLibrary(library);
        writeApplication(application);
    }

    private static void writeLibrary(Path library) throws IOException {
        Path source = library.resolve(
                "src/main/kotlin/com/example/library/PreviewLibrary.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(library.resolve("zolt.toml"), """
                [project]
                name = "library"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "%s"

                [compiler]
                args = ["-parameters", "-Xjvm-enable-preview"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KotlinCompilerCliFixture.KOTLIN_VERSION));
        Files.writeString(source, """
                package com.example.library

                object PreviewLibrary {
                    @JvmStatic
                    fun message(value: String): String = "preview-library-$value"
                }
                """);
    }

    private static void writeApplication(Path application) throws IOException {
        Path source = application.resolve(
                "src/main/kotlin/com/example/application/Application.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(application.resolve("zolt.toml"), """
                [project]
                name = "application"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "%s"

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "%s"

                [compiler]
                args = ["-parameters"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                "com.example:library" = { workspace = true }
                """.formatted(
                Runtime.version().feature(),
                MAIN_CLASS,
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KotlinCompilerCliFixture.KOTLIN_VERSION));
        Files.writeString(source, """
                package com.example.application

                import com.example.library.PreviewLibrary

                fun main(args: Array<String>) {
                    println(PreviewLibrary.message(args.single()))
                }
                """);
    }
}
