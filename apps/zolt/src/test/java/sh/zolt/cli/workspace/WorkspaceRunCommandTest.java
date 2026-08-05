package sh.zolt.cli.workspace;

import sh.zolt.cli.WorkspaceCommandFixture;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.WorkspaceCommandFixture.WorkspaceApplicationFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceRunCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void runWorkspaceMemberRunsSelectedApplicationFromClasses() throws IOException {
        WorkspaceApplicationFixture fixture = WorkspaceCommandFixture.create(tempDir, "workspace-run");

        CommandResult result = execute(
                "run",
                "--workspace",
                "--member", "apps/api",
                "--cwd", fixture.apiDir().toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "hello");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("Resolved workspace dependencies because zolt.lock was missing"));
        assertTrue(result.stdout().contains("core:hello"));
        assertTrue(result.stdout().contains("Ran com.example.api.Api in apps/api"));
        assertTrue(Files.exists(fixture.apiDir().resolve("target/classes/com/example/api/Api.class")));
        assertTrue(Files.exists(fixture.coreDir().resolve("target/classes/com/example/core/Core.class")));
        assertFalse(Files.exists(fixture.apiDir().resolve("target/api-0.1.0.jar")));
    }

    @Test
    void runWorkspacePrintsSplitJsonTimingsWhenRequested() throws IOException {
        WorkspaceApplicationFixture fixture = WorkspaceCommandFixture.create(tempDir, "workspace-run-timings");

        CommandResult result = execute(
                "run",
                "--workspace",
                "--member", "apps/api",
                "--timings",
                "--timings-format", "json",
                "--cwd", fixture.apiDir().toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "hello");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("core:hello"));
        String[] lines = result.stderr().lines().toArray(String[]::new);
        assertEquals(5, lines.length);
        assertTrue(lines[0].contains("\"phase\":\"workspace lock freshness\""));
        assertTrue(lines[0].contains("\"workspaceLockFreshness\""));
        assertTrue(lines[1].contains("\"phase\":\"plan workspace run\""));
        assertTrue(lines[1].contains("\"depth\":1"));
        assertTrue(lines[1].contains("\"includedMembers\":\"2\""));
        assertTrue(lines[1].contains("\"selectedMembers\":\"1\""));
        assertTrue(lines[2].contains("\"phase\":\"build workspace run inputs\""));
        assertTrue(lines[2].contains("\"depth\":1"));
        assertTrue(lines[2].contains("\"mainCompilationsExecuted\":\"2\""));
        assertTrue(lines[3].contains("\"phase\":\"launch workspace members\""));
        assertTrue(lines[3].contains("\"depth\":1"));
        assertTrue(lines[3].contains("\"members\":\"1\""));
        assertTrue(lines[3].contains("\"outputBytes\""));
        assertTrue(lines[4].contains("\"phase\":\"run workspace\""));
        assertTrue(lines[4].contains("\"depth\":0"));
        assertTrue(lines[4].contains("\"mainCompilationsExecuted\":\"2\""));
    }
}
