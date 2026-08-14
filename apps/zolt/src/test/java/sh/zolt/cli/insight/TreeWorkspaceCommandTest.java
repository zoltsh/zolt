package sh.zolt.cli.insight;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TreeWorkspaceCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void discoversAWorkspaceDeclaredInTheRootZoltToml() throws IOException {
        Path workspace = rootConfigWorkspace("root-config");

        CommandResult result = execute("tree", "--workspace", "--format", "json", "--cwd", workspace.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"schemaVersion\": 3"), result.stdout());
        assertTrue(result.stdout().contains("\"name\": \"demo-workspace\""), result.stdout());
        assertTrue(result.stdout().contains("\"path\": \"apps/api\""), result.stdout());
        assertTrue(result.stdout().contains("\"path\": \"modules/core\""), result.stdout());
    }

    @Test
    void discoversAWorkspaceDeclaredInLegacyZoltWorkspaceToml() throws IOException {
        Path workspace = tempDir.resolve("legacy-config");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt-workspace.toml"), TreeFixtures.WORKSPACE_CONFIG);
        TreeFixtures.workspaceMembersAndLock(workspace);

        CommandResult result = execute("tree", "--workspace", "--format", "json", "--cwd", workspace.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals(
                execute("tree", "--workspace", "--format", "json",
                        "--cwd", rootConfigWorkspace("mirror").toString()).stdout(),
                result.stdout());
    }

    @Test
    void discoversTheWorkspaceFromAMemberDirectory() throws IOException {
        Path workspace = rootConfigWorkspace("from-member");

        CommandResult result = execute(
                "tree",
                "--workspace",
                "--format", "json",
                "--directory", workspace.resolve("apps/api").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\"mode\": \"workspace\""), result.stdout());
        assertTrue(result.stdout().contains("\"lockVersion\": 6"), result.stdout());
    }

    @Test
    void printsOneTextSectionPerMember() throws IOException {
        Path workspace = rootConfigWorkspace("text");

        CommandResult result = execute("tree", "--workspace", "--cwd", workspace.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("", result.stderr());
        assertEquals("""
                demo-workspace
                apps/api
                +- com.example:core:0.1.0
                |  \\- org.example:shared:1.0.0
                |     \\- org.example:extra:2.0.0
                +- org.example:bundle:3.0.0:zip
                \\- org.example:shared:1.0.0
                   \\- org.example:extra:2.0.0

                modules/core
                +- org.example:shared:1.0.0
                \\- org.example:shared:1.0.0
                """, result.stdout());
    }

    @Test
    void reportsMissingRootLockfileActionably() throws IOException {
        Path workspace = tempDir.resolve("no-lock");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), TreeFixtures.WORKSPACE_CONFIG);
        TreeFixtures.workspaceMembersAndLock(workspace);
        Files.delete(workspace.resolve("zolt.lock"));

        CommandResult result = execute("tree", "--workspace", "--format", "json", "--cwd", workspace.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: No zolt.lock found at"), result.stderr());
        assertTrue(result.stderr().contains("Next: Run `zolt resolve --workspace` to generate it"), result.stderr());
    }

    @Test
    void reportsAMissingWorkspaceActionably() throws IOException {
        Path project = TreeFixtures.standaloneProject(tempDir.resolve("not-a-workspace"));

        CommandResult result = execute("tree", "--workspace", "--format", "json", "--cwd", project.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: No Zolt workspace was found for `zolt tree --workspace`"),
                result.stderr());
        assertTrue(result.stderr().contains("Next: Run from a workspace root"), result.stderr());
    }

    @Test
    void refusesAStaleRootLockWithoutMemberGraphEvidence() throws IOException {
        Path workspace = tempDir.resolve("stale-lock");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), TreeFixtures.WORKSPACE_CONFIG);
        TreeFixtures.workspaceMembersAndLock(workspace);
        Files.writeString(
                workspace.resolve("zolt.lock"),
                TreeFixtures.workspaceLock().replaceFirst("version = 6", "version = 4"));

        CommandResult result = execute("tree", "--workspace", "--format", "json", "--cwd", workspace.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("version 4"), result.stderr());
        assertTrue(result.stderr().contains("optional-boundary"), result.stderr());
        assertTrue(result.stderr().contains("zolt resolve --workspace"), result.stderr());
    }

    @Test
    void refusesARootLockWhoseEdgeNamesNoLockedPackage() throws IOException {
        Path workspace = tempDir.resolve("dangling-edge");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), TreeFixtures.WORKSPACE_CONFIG);
        TreeFixtures.workspaceMembersAndLock(workspace);
        Files.writeString(
                workspace.resolve("zolt.lock"),
                TreeFixtures.workspaceLock().replace(
                        "dependencies = [\"org.example:extra:2.0.0:jar:compile\"]",
                        "dependencies = [\"org.example:missing:9.9.9:jar:compile\"]"));

        CommandResult result = execute("tree", "--workspace", "--format", "json", "--cwd", workspace.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: Dangling dependency edge"), result.stderr());
        assertTrue(result.stderr().contains("Next: Run `zolt resolve --workspace`"), result.stderr());
    }

    private Path rootConfigWorkspace(String name) throws IOException {
        Path workspace = tempDir.resolve(name);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), TreeFixtures.WORKSPACE_CONFIG);
        return TreeFixtures.workspaceMembersAndLock(workspace);
    }
}
