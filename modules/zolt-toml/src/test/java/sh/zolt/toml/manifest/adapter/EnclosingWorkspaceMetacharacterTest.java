package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltConfigException;

/**
 * Design §6.3/§6.5: the member-directory read path shares the workspace expander's grammar. An
 * unrelated sibling directory whose name cannot be a member path must not break a command run inside
 * a perfectly valid member.
 */
final class EnclosingWorkspaceMetacharacterTest {
    private final ManifestProjectConfigLoader loader = new ManifestProjectConfigLoader();

    @TempDir
    private Path tempDir;

    @Test
    void metacharSiblingDirectoryDoesNotBreakAMemberDirectoryCommand() throws IOException {
        Path member = workspace();
        Files.createDirectories(tempDir.resolve("apps/notes[draft]"));
        Files.createDirectories(tempDir.resolve("apps/we?rd"));

        ProjectConfig config = loader.loadProject(member);

        assertEquals("api", config.project().name());
        assertEquals("com.example", config.project().group());
    }

    @Test
    void bracketSiblingWithAManifestIsAnOrdinaryMember() throws IOException {
        Path member = workspace();
        member("apps/notes[draft]", "notes");

        assertEquals("api", loader.loadProject(member).project().name());
        assertEquals(
                "notes",
                loader.loadProject(tempDir.resolve("apps/notes[draft]")).project().name());
    }

    @Test
    void manifestBearingNonportableSiblingFailsActionably() throws IOException {
        Path member = workspace();
        member("apps/we?rd", "weird");

        ZoltConfigException failure =
                assertThrows(ZoltConfigException.class, () -> loader.loadProject(member));

        assertTrue(failure.getMessage().contains("apps/we?rd"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Rename the directory"), failure.getMessage());
    }

    private Path workspace() throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """);
        return member("apps/api", "api");
    }

    private Path member(String path, String name) throws IOException {
        Path directory = tempDir.resolve(path);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "%s"
                """.formatted(name));
        return directory;
    }
}
