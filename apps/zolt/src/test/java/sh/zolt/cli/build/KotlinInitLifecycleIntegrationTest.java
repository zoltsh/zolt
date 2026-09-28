package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** Real-compiler lifecycle for the standalone and workspace templates emitted by Kotlin init. */
final class KotlinInitLifecycleIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void generatedProjectsResolveBuildTestAndRunWithRealKotlinCompiler() throws Exception {
        Path standalone = tempDir.resolve("hello");
        Path workspace = tempDir.resolve("platform");
        Path member = workspace.resolve("apps/platform");
        Path cache = tempDir.resolve("cache");

        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publishInitProject(repository);

            CommandResult standaloneInit = execute(
                    "init",
                    "hello",
                    "--language", "kotlin",
                    "--group", "when.is",
                    "--directory", tempDir.toString());
            assertEquals(0, standaloneInit.exitCode(), standaloneInit.stderr());
            useRepository(standalone.resolve("zolt.toml"), repository);

            CommandResult workspaceInit = execute(
                    "init",
                    "platform",
                    "--workspace",
                    "--language", "kotlin",
                    "--directory", tempDir.toString());
            assertEquals(0, workspaceInit.exitCode(), workspaceInit.stderr());
            useRepository(workspace.resolve("zolt.toml"), repository);

            assertKotlinTemplate(standalone, "when/is");
            assertKotlinTemplate(member, "com/example");

            assertSuccess(execute(
                    "resolve",
                    "--cwd", standalone.toString(),
                    "--cache-root", cache.toString()));
            assertSuccess(execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString()));
            repository.clearAuthorizations();
            repository.close();

            assertProjectLifecycle(standalone, cache, false);
            assertProjectLifecycle(workspace, cache, true);

            assertTrue(Files.isRegularFile(
                    standalone.resolve("target/classes/when/is/Main.class")));
            assertTrue(Files.isRegularFile(
                    standalone.resolve("target/test-classes/when/is/MainTest.class")));
            assertTrue(Files.isRegularFile(
                    member.resolve("target/classes/com/example/Main.class")));
            assertTrue(Files.isRegularFile(
                    member.resolve("target/test-classes/com/example/MainTest.class")));
            assertKotlinModule(standalone.resolve("target/classes"));
            assertKotlinModule(standalone.resolve("target/test-classes"));
            assertKotlinModule(member.resolve("target/classes"));
            assertKotlinModule(member.resolve("target/test-classes"));
        }
    }

    private static void assertProjectLifecycle(
            Path root, Path cache, boolean workspace) {
        assertSuccess(execute(command(
                "build", root, cache, workspace, true)));

        CommandResult test = execute(command(
                "test", root, cache, workspace, true));
        assertEquals(0, test.exitCode(), test.stderr());
        assertTrue(test.stdout().contains("Tests passed"), test.stdout());
        assertTrue(
                test.stdout().matches("(?s).*\\b1 tests successful\\b.*"),
                test.stdout());

        CommandResult run = execute(command(
                "run", root, cache, workspace, false));
        assertEquals(0, run.exitCode(), run.stderr());
        assertTrue(
                run.stdout().contains("Hello from " + root.getFileName() + "!"),
                run.stdout());
    }

    private static void assertKotlinTemplate(Path project, String packagePath) {
        assertTrue(Files.isRegularFile(
                project.resolve("src/main/kotlin/" + packagePath + "/Main.kt")));
        assertTrue(Files.isRegularFile(
                project.resolve("src/test/kotlin/" + packagePath + "/MainTest.kt")));
        assertTrue(Files.notExists(project.resolve("src/main/java")));
        assertTrue(Files.notExists(project.resolve("src/test/java")));
    }

    private static void assertKotlinModule(Path output) throws IOException {
        try (Stream<Path> entries = Files.list(output.resolve("META-INF"))) {
            assertTrue(
                    entries.anyMatch(path -> path.getFileName().toString().endsWith(".kotlin_module")),
                    () -> "Missing Kotlin module metadata under " + output);
        }
    }

    private static String[] command(
            String command,
            Path root,
            Path cache,
            boolean workspace,
            boolean noBuildCache) {
        java.util.ArrayList<String> arguments = new java.util.ArrayList<>();
        arguments.add(command);
        if (workspace) {
            arguments.add("--workspace");
        }
        if (noBuildCache) {
            arguments.add("--no-build-cache");
        }
        arguments.add("--cwd");
        arguments.add(root.toString());
        arguments.add("--cache-root");
        arguments.add(cache.toString());
        return arguments.toArray(String[]::new);
    }

    private static void useRepository(
            Path manifest, CliTestRepository repository) throws IOException {
        Files.writeString(manifest, Files.readString(manifest) + """

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"
                """.formatted(repository.baseUri()));
    }

    private static void assertSuccess(CommandResult result) {
        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("", result.stderr());
    }
}
