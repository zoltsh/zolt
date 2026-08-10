package sh.zolt.cli.quality;

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

final class CheckLockfileCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkLockfileReportsMissingLockfile() throws IOException {
        Path projectDir = tempDir.resolve("check-missing-lock");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-missing-lock"));

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "lockfile");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error lockfile zolt.lock zolt.lock is missing."));
        assertTrue(result.stdout().contains("next: Run `zolt resolve`."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkJsonIncludesMachineReadableBlockers() throws IOException {
        Path projectDir = tempDir.resolve("check-json-blockers");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-json-blockers"));

        CommandResult result = execute(
                "check",
                "--cwd", projectDir.toString(),
                "--check", "lockfile",
                "--format", "json");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("\"status\":\"failed\""));
        assertTrue(result.stdout().contains("\"checks\":["));
        assertTrue(result.stdout().contains("\"blockers\":[{"));
        assertTrue(result.stdout().indexOf("\"blockers\"") > result.stdout().indexOf("\"checks\""));
        assertTrue(result.stdout().contains("\"id\":\"lockfile\""));
        assertTrue(result.stdout().contains("\"severity\":\"error\""));
        assertTrue(result.stdout().contains("\"member\":null"));
        assertTrue(result.stdout().contains("\"subject\":\"zolt.lock\""));
        assertTrue(result.stdout().contains("\"message\":\"zolt.lock is missing.\""));
        assertTrue(result.stdout().contains("\"nextStep\":\"Run `zolt resolve`.\""));
        assertEquals("", result.stderr());
    }

    @Test
    void checkLockfilePassesWhenLockedResolveMatches() throws IOException {
        Path projectDir = tempDir.resolve("check-lock-ok");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-lock-ok"));
        CommandResult resolve = execute("resolve", "--cwd", projectDir.toString(), "--cache-root", tempDir.resolve("cache").toString());
        assertEquals(0, resolve.exitCode());

        CommandResult result = execute(
                "check",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--check", "lockfile");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok lockfile zolt.lock zolt.lock matches zolt.toml."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkLockfileReportsStaleLockfile() throws IOException {
        Path projectDir = tempDir.resolve("check-lock-stale");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-lock-stale"));
        CommandResult resolve = execute("resolve", "--cwd", projectDir.toString(), "--cache-root", tempDir.resolve("cache").toString());
        assertEquals(0, resolve.exitCode());
        Files.writeString(projectDir.resolve("zolt.lock"), Files.readString(projectDir.resolve("zolt.lock")) + "# stale\n");

        CommandResult result = execute(
                "check",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--check", "lockfile");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error lockfile zolt.lock zolt.lock is out of date."));
        assertTrue(result.stdout().contains("next: Run `zolt resolve`."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkWorkspaceLockfileVerifiesRootLockfile() throws IOException {
        Path workspaceDir = tempDir.resolve("check-workspace-lock");
        Path memberDir = workspaceDir.resolve("modules/core");
        Files.createDirectories(memberDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "check-workspace-lock"
                members = ["modules/core"]
                """);
        Files.writeString(memberDir.resolve("zolt.toml"), memberConfig("core"));
        CommandResult resolve = execute("resolve", "--workspace", "--cwd", workspaceDir.toString(), "--cache-root", tempDir.resolve("cache").toString());
        assertEquals(0, resolve.exitCode());

        CommandResult result = execute(
                "check",
                "--workspace",
                "--cwd", workspaceDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--check", "lockfile");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok lockfile zolt.lock Workspace zolt.lock matches the workspace config and member zolt.toml files."));
        assertEquals("", result.stderr());
        assertFalse(Files.exists(memberDir.resolve("zolt.lock")));
    }
}
