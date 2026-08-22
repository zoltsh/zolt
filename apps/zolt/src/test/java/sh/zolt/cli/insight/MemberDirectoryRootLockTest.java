package sh.zolt.cli.insight;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Design §4.5: a workspace has exactly one authoritative lockfile, at its root, and no command creates
 * or consumes a member-local {@code zolt.lock}. Every read-only command run from a member directory
 * therefore projects that member's graph out of the root lock instead of demanding a lock beside it.
 */
final class MemberDirectoryRootLockTest {
    @TempDir
    private Path tempDir;

    @Test
    void treeFromAMemberDirectoryProjectsThatMembersRootsFromTheRootLock() throws IOException {
        Path workspace = workspace("tree-member");

        CommandResult api = execute("tree", "--cwd", workspace.resolve("apps/api").toString());

        assertEquals(0, api.exitCode(), api.stderr());
        assertEquals("", api.stderr());
        assertTrue(api.stdout().startsWith("com.example:api:0.1.0\n"), api.stdout());
        assertTrue(api.stdout().contains("com.example:core:0.1.0"), api.stdout());
        assertTrue(api.stdout().contains("org.example:bundle:3.0.0"), api.stdout());
        // modules/core's test-classified root belongs to that member's graph, not this one.
        assertFalse(api.stdout().contains("tests"), api.stdout());
    }

    @Test
    void treeFromASecondMemberProjectsItsOwnRootsInstead() throws IOException {
        Path workspace = workspace("tree-second-member");

        CommandResult core = execute("tree", "--cwd", workspace.resolve("modules/core").toString());

        assertEquals(0, core.exitCode(), core.stderr());
        assertTrue(core.stdout().startsWith("com.example:core:0.1.0\n"), core.stdout());
        assertTrue(core.stdout().contains("org.example:shared:1.0.0"), core.stdout());
        assertFalse(core.stdout().contains("org.example:bundle"), core.stdout());
    }

    @Test
    void whyFromAMemberDirectoryExplainsThatMembersPath() throws IOException {
        Path workspace = workspace("why-member");

        CommandResult result =
                execute("why", "org.example:extra", "--cwd", workspace.resolve("apps/api").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("com.example:api:0.1.0"), result.stdout());
        assertTrue(result.stdout().contains("org.example:extra:2.0.0"), result.stdout());
    }

    @Test
    void conflictsPolicyLicensesAndSbomReadTheRootLockFromAMemberDirectory() throws IOException {
        Path member = workspace("supply-chain-member").resolve("apps/api");

        assertSucceeds(execute("conflicts", "--cwd", member.toString()));
        assertSucceeds(execute("policy", "--cwd", member.toString()));
        assertSucceeds(execute(
                "licenses", "--offline", "--cwd", member.toString(),
                "--cache-root", tempDir.resolve("cache").toString()));
        CommandResult sbom = execute(
                "sbom", "--offline", "--cwd", member.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        assertSucceeds(sbom);
        assertTrue(sbom.stdout().contains("\"bomFormat\": \"CycloneDX\""), sbom.stdout());
    }

    @Test
    void aStandaloneProjectStillReadsItsOwnLock() throws IOException {
        Path project = TreeFixtures.standaloneProject(tempDir.resolve("standalone"));

        CommandResult tree = execute("tree", "--cwd", project.toString());
        CommandResult why = execute("why", "com.example:lib", "--cwd", project.toString());
        CommandResult conflicts = execute("conflicts", "--cwd", project.toString());

        assertSucceeds(tree);
        assertSucceeds(why);
        assertSucceeds(conflicts);
        assertTrue(tree.stdout().startsWith("com.example:demo:0.1.0\n"), tree.stdout());
        assertTrue(why.stdout().contains("com.example:lib:2.0.0"), why.stdout());
    }

    private static void assertSucceeds(CommandResult result) {
        assertEquals(0, result.exitCode(), result.stderr());
    }

    private Path workspace(String name) throws IOException {
        Path root = tempDir.resolve(name);
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.toml"), TreeFixtures.WORKSPACE_CONFIG);
        return TreeFixtures.workspaceMembersAndLock(root);
    }
}
