package sh.zolt.cli.workspace;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceResolveCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolveWorkspaceWritesRootLockfile() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace");
        Path apiDir = workspaceDir.resolve("apps/api");
        Path coreDir = workspaceDir.resolve("modules/core");
        Files.createDirectories(apiDir);
        Files.createDirectories(coreDir);
        Files.writeString(workspaceDir.resolve("zolt-workspace.toml"), """
                [workspace]
                name = "workspace"

                [workspace.members]
                default = ["apps/api"]
                include = ["apps/api", "modules/core"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api"));
        Files.writeString(coreDir.resolve("zolt.toml"), memberConfig("core"));

        CommandResult result = execute(
                "resolve",
                "--workspace",
                "--cwd", apiDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("Resolved 0 packages"));
        assertTrue(result.stdout().contains("wrote " + workspaceDir.resolve("zolt.lock")));
        assertTrue(Files.readString(workspaceDir.resolve("zolt.lock"))
                .contains("projectResolutionFingerprint = \"sha256:"));
        assertFalse(Files.exists(apiDir.resolve("zolt.lock")));
        assertFalse(Files.exists(coreDir.resolve("zolt.lock")));
    }

    @Test
    void resolveWorkspaceDiscoversRootZoltTomlWorkspaceConfig() throws IOException {
        Path workspaceDir = tempDir.resolve("root-workspace");
        Path apiDir = workspaceDir.resolve("apps/api");
        Path coreDir = workspaceDir.resolve("modules/core");
        Files.createDirectories(apiDir);
        Files.createDirectories(coreDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "workspace"

                [workspace.members]
                default = ["apps/api"]
                include = ["apps/api", "modules/core"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api"));
        Files.writeString(coreDir.resolve("zolt.toml"), memberConfig("core"));

        CommandResult result = execute(
                "resolve",
                "--workspace",
                "--cwd", apiDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("Resolved 0 packages"));
        assertTrue(result.stdout().contains("wrote " + workspaceDir.resolve("zolt.lock")));
        assertTrue(Files.readString(workspaceDir.resolve("zolt.lock"))
                .contains("projectResolutionFingerprint = \"sha256:"));
        assertFalse(Files.exists(apiDir.resolve("zolt.lock")));
        assertFalse(Files.exists(coreDir.resolve("zolt.lock")));
    }

    @Test
    void repeatedResolveReportsTheLockCurrentInsteadOfRewritingIt() throws IOException {
        Path workspaceDir = tempDir.resolve("current-workspace");
        Path apiDir = workspaceDir.resolve("apps/api");
        Files.createDirectories(apiDir);
        Files.writeString(workspaceDir.resolve("zolt-workspace.toml"), """
                [workspace]
                name = "workspace"

                [workspace.members]
                default = ["apps/api"]
                include = ["apps/api"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api"));
        Path cacheRoot = tempDir.resolve("cache-current");
        execute("resolve", "--workspace", "--cwd", apiDir.toString(), "--cache-root", cacheRoot.toString());
        String committed = Files.readString(workspaceDir.resolve("zolt.lock"));

        CommandResult result = execute(
                "resolve", "--workspace", "--cwd", apiDir.toString(), "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("up to date " + workspaceDir.resolve("zolt.lock")));
        assertFalse(result.stdout().contains("wrote " + workspaceDir.resolve("zolt.lock")));
        assertEquals(committed, Files.readString(workspaceDir.resolve("zolt.lock")));
    }

    @Test
    void resolveOnWorkspaceRootWithoutWorkspaceFlagHintsWorkspace() throws IOException {
        Path workspaceDir = writeWorkspaceRootConfig("hint-resolve");

        CommandResult result = execute(
                "resolve",
                "--cwd", workspaceDir.toString(),
                "--cache-root", tempDir.resolve("cache-hint-resolve").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: This zolt.toml declares a [workspace], not a [project]"));
        assertTrue(result.stderr().contains("Next: Re-run the command with --workspace"));
        assertFalse(result.stderr().contains("Missing required section [project]"));
    }

    @Test
    void buildOnWorkspaceRootWithoutWorkspaceFlagHintsWorkspace() throws IOException {
        Path workspaceDir = writeWorkspaceRootConfig("hint-build");

        CommandResult result = execute(
                "build",
                "--cwd", workspaceDir.toString(),
                "--cache-root", tempDir.resolve("cache-hint-build").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: This zolt.toml declares a [workspace], not a [project]"));
        assertTrue(result.stderr().contains("Next: Re-run the command with --workspace"));
        assertFalse(result.stderr().contains("Missing required section [project]"));
    }

    @Test
    void testOnWorkspaceRootWithoutWorkspaceFlagHintsWorkspace() throws IOException {
        Path workspaceDir = writeWorkspaceRootConfig("hint-test");

        CommandResult result = execute(
                "test",
                "--cwd", workspaceDir.toString(),
                "--cache-root", tempDir.resolve("cache-hint-test").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: This zolt.toml declares a [workspace], not a [project]"));
        assertTrue(result.stderr().contains("Next: Re-run the command with --workspace"));
        assertFalse(result.stderr().contains("Missing required section [project]"));
    }

    private Path writeWorkspaceRootConfig(String name) throws IOException {
        Path workspaceDir = tempDir.resolve(name);
        Path apiDir = workspaceDir.resolve("apps/api");
        Path coreDir = workspaceDir.resolve("modules/core");
        Files.createDirectories(apiDir);
        Files.createDirectories(coreDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "workspace"

                [workspace.members]
                default = ["apps/api"]
                include = ["apps/api", "modules/core"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api"));
        Files.writeString(coreDir.resolve("zolt.toml"), memberConfig("core"));
        return workspaceDir;
    }
}
