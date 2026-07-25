package sh.zolt.cli.workspace;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.writeFakeConsoleJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GeneratedWorkspaceInitFlowTest {
    @TempDir
    private Path tempDir;

    @Test
    void generatedWorkspaceResolvesBuildsTestsAndRuns() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");

        CommandResult init = execute(
                "init",
                "--workspace",
                "--directory", tempDir.toString(),
                "platform");

        Path workspaceDir = tempDir.resolve("platform");
        Path appDir = workspaceDir.resolve("apps/platform");
        assertEquals(0, init.exitCode());
        assertTrue(init.stdout().contains("Created Zolt workspace at"));
        assertTrue(Files.exists(workspaceDir.resolve("zolt.toml")));
        assertFalse(Files.exists(workspaceDir.resolve("zolt-workspace.toml")));
        assertTrue(Files.exists(appDir.resolve("zolt.toml")));

        String workspaceToml = Files.readString(workspaceDir.resolve("zolt.toml"));
        assertTrue(workspaceToml.contains("[workspace]"));
        assertTrue(workspaceToml.contains("members = [\"apps/platform\"]"));
        assertTrue(workspaceToml.contains("defaultMembers = [\"apps/platform\"]"));

        CommandResult resolve = execute(
                "resolve",
                "--workspace",
                "--cwd", workspaceDir.toString(),
                "--cache-root", cacheRoot.toString());
        assertEquals(0, resolve.exitCode());
        assertEquals("", resolve.stderr());
        assertTrue(resolve.stdout().contains("Resolved 0 packages"));
        assertTrue(Files.exists(workspaceDir.resolve("zolt.lock")));

        CommandResult build = execute(
                "build",
                "--workspace",
                "--cwd", workspaceDir.toString(),
                "--cache-root", cacheRoot.toString());
        assertEquals(0, build.exitCode());
        assertEquals("", build.stderr());
        assertTrue(build.stdout().contains("Compiled 1 main source files in apps/platform"));
        assertTrue(Files.exists(appDir.resolve("target/classes/com/example/Main.class")));

        CommandResult run = execute(
                "run",
                "--workspace",
                "--cwd", workspaceDir.toString(),
                "--cache-root", cacheRoot.toString());
        assertEquals(0, run.exitCode());
        assertEquals("", run.stderr());
        assertTrue(run.stdout().contains("Hello from platform!"));
        assertTrue(run.stdout().contains("Ran com.example.Main in apps/platform"));

        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        writeGeneratedWorkspaceTestLockfile(workspaceDir);
        CommandResult test = execute(
                "test",
                "--workspace",
                "--cwd", workspaceDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, test.exitCode());
        assertEquals("", test.stderr());
        assertTrue(test.stdout().contains("fake console"));
        assertTrue(test.stdout().contains("Tests passed in apps/platform"));
        assertTrue(test.stdout().contains("Tests passed for 1 workspace members"));
        assertTrue(Files.exists(appDir.resolve("target/test-classes/com/example/MainTest.class")));
    }

    private static void writeGeneratedWorkspaceTestLockfile(Path workspaceDir) throws IOException {
        Files.writeString(workspaceDir.resolve("zolt.lock"), """
                version = 5

                [[package]]
                id = "org.junit.platform:junit-platform-console-standalone"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"
                dependencies = []
                """);
    }
}
