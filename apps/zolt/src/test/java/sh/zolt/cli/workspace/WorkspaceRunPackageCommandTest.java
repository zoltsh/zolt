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

final class WorkspaceRunPackageCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void runPackageWorkspaceMemberRunsSelectedPackagedApplication() throws IOException {
        WorkspaceApplicationFixture fixture = WorkspaceCommandFixture.create(tempDir, "workspace-run-package");

        CommandResult result = execute(
                "run-package",
                "--workspace",
                "--member", "apps/api",
                "--cwd", fixture.apiDir().toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "hello");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("Resolved workspace dependencies because zolt.lock was missing"));
        assertTrue(result.stdout().contains("core:hello"));
        assertTrue(result.stdout().contains("Ran packaged com.example.api.Api in apps/api"));
        assertTrue(result.stdout().contains("→ from "));
        assertTrue(Files.exists(fixture.apiDir().resolve("target/api-0.1.0.jar")));
        assertFalse(Files.exists(fixture.coreDir().resolve("target/core-0.1.0.jar")));
    }

    @Test
    void runPackageWorkspacePrintsSplitJsonTimingsWhenRequested() throws IOException {
        WorkspaceApplicationFixture fixture = WorkspaceCommandFixture.create(tempDir, "workspace-run-package-timings");

        CommandResult result = execute(
                "run-package",
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
        assertEquals(6, lines.length);
        assertTrue(lines[0].contains("\"phase\":\"workspace lock freshness\""));
        assertTrue(lines[0].contains("\"workspaceLockFreshness\""));
        assertTrue(lines[1].contains("\"phase\":\"plan workspace run packages\""));
        assertTrue(lines[1].contains("\"depth\":1"));
        assertTrue(lines[1].contains("\"includedMembers\":\"2\""));
        assertTrue(lines[1].contains("\"selectedMembers\":\"1\""));
        assertTrue(lines[2].contains("\"phase\":\"build workspace run-package inputs\""));
        assertTrue(lines[2].contains("\"depth\":1"));
        assertTrue(lines[2].contains("\"mainCompilationsExecuted\":\"2\""));
        assertTrue(lines[3].contains("\"phase\":\"assemble workspace run packages\""));
        assertTrue(lines[3].contains("\"depth\":1"));
        assertTrue(lines[3].contains("\"entries\":\"1\""));
        assertTrue(lines[4].contains("\"phase\":\"launch workspace packages\""));
        assertTrue(lines[4].contains("\"depth\":1"));
        assertTrue(lines[4].contains("\"members\":\"1\""));
        assertTrue(lines[4].contains("\"outputBytes\""));
        assertTrue(lines[5].contains("\"phase\":\"run workspace packages\""));
        assertTrue(lines[5].contains("\"depth\":0"));
        assertTrue(lines[5].contains("\"mainCompilationsExecuted\":\"2\""));
    }
}
