package sh.zolt.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.workspace.discovery.ManifestProjectLoader;

/**
 * Design §4.5: the command boundary carries two roots. This pins the derivation itself, so the
 * commands built on it can assert routing rather than re-deriving member identity each time.
 */
final class ProjectCommandContextTest {
    @TempDir
    private Path tempDir;

    @Test
    void standaloneProjectOwnsItsOwnLock() throws IOException {
        Path project = tempDir.resolve("standalone");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "standalone"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);

        ProjectCommandContext context = ProjectCommandContext.load(new ManifestProjectLoader(), project);

        assertEquals(project, context.projectRoot());
        assertEquals(Optional.empty(), context.workspaceRoot());
        assertEquals(project, context.lockRoot());
        assertEquals(project.resolve("zolt.lock"), context.lockfilePath());
        assertEquals(".", context.memberPath());
        assertFalse(context.workspaceMember());
        assertEquals("zolt resolve", context.resolveCommand());
    }

    @Test
    void memberProjectIsGovernedByTheWorkspaceRootLock() throws IOException {
        Path member = workspaceMember();

        ProjectCommandContext context = ProjectCommandContext.load(new ManifestProjectLoader(), member);

        assertEquals(member, context.projectRoot());
        assertEquals(Optional.of(member.getParent().getParent()), context.workspaceRoot());
        assertEquals(member.getParent().getParent(), context.lockRoot());
        assertEquals(member.getParent().getParent().resolve("zolt.lock"), context.lockfilePath());
        assertEquals("apps/api", context.memberPath());
        assertTrue(context.workspaceMember());
        assertEquals("zolt resolve --workspace", context.resolveCommand());
    }

    /** The member selection a routed command hands its workspace service: this member, expanded. */
    @Test
    void memberSelectionNamesThisMemberAndExpandsItsProviders() throws IOException {
        Path member = workspaceMember();

        ProjectCommandContext context = ProjectCommandContext.load(new ManifestProjectLoader(), member);

        assertEquals(List.of("apps/api"), context.memberSelection().members());
        assertFalse(context.memberSelection().all());
        assertFalse(
                context.memberSelection().exact(),
                "a member command builds the workspace providers it compiles against");
    }

    private Path workspaceMember() throws IOException {
        Path root = Files.createTempDirectory(tempDir, "workspace-");
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """);
        Path member = root.resolve("apps/api");
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "api"
                """);
        return member;
    }
}
