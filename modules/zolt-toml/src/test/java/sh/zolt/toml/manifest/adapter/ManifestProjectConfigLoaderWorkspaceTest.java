package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltConfigException;

/**
 * Design §4.5 "Command discovery": a directory a workspace expanded into a member is evaluated with
 * the workspace root's shared configuration whether or not {@code --workspace} was supplied. These
 * cases pin the workspace-aware load path that every "the project here" command reads through.
 */
final class ManifestProjectConfigLoaderWorkspaceTest {
    private final ManifestProjectConfigLoader loader = new ManifestProjectConfigLoader();

    @TempDir
    private Path tempDir;

    @Test
    void memberInheritingIdentityFromTheRootComposesAgainstThatRoot() throws IOException {
        Path member = workspace("""
                [workspace]
                name = "platform"

                [workspace.members]
                default = ["apps/platform"]
                include = ["apps/platform"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """, "apps/platform", """
                [project]
                name = "platform"
                """);

        ProjectConfig config = loader.loadProject(member);

        assertEquals("platform", config.project().name());
        assertEquals("com.example", config.project().group());
        assertEquals("0.1.0", config.project().version());
        assertEquals("21", config.project().java());
    }

    @Test
    void theSameMemberIsRejectedWhenComposedStandalone() throws IOException {
        Path member = workspace("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/platform"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """, "apps/platform", """
                [project]
                name = "platform"
                """);

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> loader.load(member.resolve("zolt.toml")));

        assertTrue(failure.getMessage().contains("project.version"), failure.getMessage());
    }

    /** Design §4.4/§6.9: a manifest with both [workspace] and [project] composes as its `.` member. */
    @Test
    void rootProjectWorkspaceComposesAsTheDotMember() throws IOException {
        Path root = tempDir.resolve("root-project");
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                default = ["."]
                include = [".", "modules/*"]

                [workspace.project]
                group = "com.example"
                version = "1.4.0"
                java = 21

                [project]
                name = "platform-root"
                """);

        ProjectConfig config = loader.loadProject(root);

        assertEquals("platform-root", config.project().name());
        assertEquals("com.example", config.project().group());
        assertEquals("1.4.0", config.project().version());
    }

    @Test
    void aDirectoryNoIncludeSelectsStaysStandalone() throws IOException {
        Path outsider = workspace("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]
                exclude = ["apps/sandbox"]
                """, "apps/sandbox", """
                [project]
                name = "sandbox"
                version = "9.9.9"
                group = "com.sandbox"
                java = 21
                """);

        ProjectConfig config = loader.loadProject(outsider);

        assertEquals("com.sandbox", config.project().group());
        assertEquals("9.9.9", config.project().version());
    }

    @Test
    void aProjectOutsideEveryWorkspaceComposesStandalone() throws IOException {
        Path project = tempDir.resolve("solo");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "solo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);

        assertEquals("solo", loader.loadProject(project).project().name());
        assertEquals(Optional.empty(), loader.enclosingWorkspaceRoot(project));
    }

    @Test
    void reportsTheWorkspaceRootAMemberBelongsTo() throws IOException {
        Path member = workspace("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """, "apps/platform", """
                [project]
                name = "platform"
                """);

        assertEquals(
                Optional.of(member.getParent().getParent()),
                loader.enclosingWorkspaceRoot(member));
    }

    private Path workspace(String rootSource, String memberPath, String memberSource)
            throws IOException {
        Path root = Files.createTempDirectory(tempDir, "workspace-");
        Files.writeString(root.resolve("zolt.toml"), rootSource);
        Path member = root.resolve(memberPath);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), memberSource);
        return member;
    }
}
