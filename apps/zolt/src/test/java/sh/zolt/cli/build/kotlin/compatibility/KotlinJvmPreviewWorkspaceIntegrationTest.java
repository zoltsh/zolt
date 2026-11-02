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
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Packaged CLI proof for preview compilation and owned runtimes through workspace planning. */
final class KotlinJvmPreviewWorkspaceIntegrationTest {
    private static final String MEMBER = "apps/preview";
    private static final String MAIN_CLASS = "com.example.WorkspacePreviewKt";

    @TempDir
    private Path tempDir;

    @Test
    void runsPreviewApplicationPackageAndTestsOfflineThroughWorkspaceSnapshots() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path member = workspace.resolve(MEMBER);
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeWorkspace(workspace, member, repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), combined(resolve));
            repository.clearAuthorizations();
            repository.close();

            CommandResult run = run(workspace, cache, "one");
            assertRun(run, "preview-workspace-one", "Ran " + MAIN_CLASS + " in " + MEMBER);
            assertPreviewClass(member.resolve(
                    "target/classes/com/example/WorkspacePreviewKt.class"));
            assertPreviewClass(member.resolve(
                    "target/classes/com/example/JavaPreview.class"));

            CommandResult warmRun = run(workspace, cache, "two");
            assertRun(
                    warmRun,
                    "preview-workspace-two",
                    "Ran " + MAIN_CLASS + " in " + MEMBER);

            CommandResult runPackage = execute(
                    "run-package",
                    "--workspace",
                    "--member", MEMBER,
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString(),
                    "--",
                    "three");
            assertRun(
                    runPackage,
                    "preview-workspace-three",
                    "Ran packaged " + MAIN_CLASS + " in " + MEMBER);

            CommandResult test = execute(
                    "test",
                    "--workspace",
                    "--member", MEMBER,
                    "--no-build-cache",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, test.exitCode(), combined(test));
            assertTrue(test.stdout().contains("Tests passed"), test.stdout());
            assertTrue(test.stdout().matches("(?s).*\\b1 tests successful\\b.*"), test.stdout());
            assertPreviewClass(member.resolve(
                    "target/test-classes/com/example/WorkspacePreviewTest.class"));
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static CommandResult run(Path workspace, Path cache, String argument) {
        return execute(
                "run",
                "--workspace",
                "--member", MEMBER,
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

    private static void assertPreviewClass(Path classFile) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        assertTrue(bytes.length >= 8);
        assertEquals(0xffff, unsignedShort(bytes, 4));
        assertEquals(Runtime.version().feature() + 44, unsignedShort(bytes, 6));
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 8 | bytes[offset + 1] & 0xff;
    }

    private static void writeWorkspace(
            Path workspace,
            Path member,
            CliTestRepository repository) throws IOException {
        Files.createDirectories(workspace);
        Files.createDirectories(member);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "kotlin-jvm-preview-workspace"

                [workspace.members]
                include = ["apps/preview"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"
                """.formatted(repository.baseUri()));
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "preview"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "%s"

                [build]
                sources = ["src/main/kotlin", "src/main/java"]

                [test.sources]
                kotlin = ["src/test/kotlin"]

                [toolchain.kotlin]
                version = "%s"

                [compiler]
                args = ["-parameters", "-Xjvm-enable-preview"]

                [compiler.test]
                args = ["-parameters", "-Xjvm-enable-preview"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"

                [dependencies.test]
                "org.junit.platform:junit-platform-console-standalone" = "%s"
                """.formatted(
                Runtime.version().feature(),
                MAIN_CLASS,
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
        writeSources(member);
    }

    private static void writeSources(Path member) throws IOException {
        Path kotlin = member.resolve("src/main/kotlin/com/example/WorkspacePreview.kt");
        Path java = member.resolve("src/main/java/com/example/JavaPreview.java");
        Path test = member.resolve("src/test/kotlin/com/example/WorkspacePreviewTest.kt");
        Files.createDirectories(kotlin.getParent());
        Files.createDirectories(java.getParent());
        Files.createDirectories(test.getParent());
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
                        return STR."preview-workspace-\\{value}";
                    }
                }
                """);
        Files.writeString(test, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class WorkspacePreviewTest {
                    @Test
                    fun loadsPreviewMarkedMainAndTestClasses() {
                        assertEquals("preview-workspace-test", JavaPreview.message("test"))
                    }
                }
                """);
    }
}
