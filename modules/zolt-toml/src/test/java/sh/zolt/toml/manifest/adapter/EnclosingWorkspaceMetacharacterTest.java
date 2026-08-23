package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltConfigException;

/**
 * Design §6.3/§6.5: the member-directory read path shares the workspace expander's grammar. An
 * unrelated sibling directory whose name cannot be a member path must not break a command run inside
 * a perfectly valid member.
 *
 * <p><strong>Portability.</strong> Brackets and braces are ordinary literal characters in a path on
 * every filesystem Zolt supports, so the sibling cases that carry the load here use them and run
 * everywhere. {@code ?} and {@code *} are different in kind: Windows rejects them when the
 * {@link Path} is constructed, before any Zolt code runs, so a physical {@code we?rd} directory is a
 * statement about the filesystem rather than evidence about the product. The rejection itself is
 * therefore asserted from the model, on every platform, and only the physical fixture is skipped
 * where the filesystem cannot hold it.
 */
final class EnclosingWorkspaceMetacharacterTest {
    private final ManifestProjectConfigLoader loader = new ManifestProjectConfigLoader();

    @TempDir
    private Path tempDir;

    @Test
    void metacharSiblingDirectoryDoesNotBreakAMemberDirectoryCommand() throws IOException {
        Path member = workspace();
        Files.createDirectories(tempDir.resolve("apps/notes[draft]"));
        Files.createDirectories(tempDir.resolve("apps/{scratch}"));

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

    /**
     * Platform-independent: the grammar this read path shares rejects {@code ?} as a member identity on
     * every platform, including the ones whose filesystem could never produce such a directory.
     */
    @Test
    void patternSyntaxCannotCarryMemberIdentityOnAnyPlatform() {
        assertEquals(Optional.empty(), WorkspaceMemberPath.problem("apps/notes[draft]"));
        assertTrue(
                WorkspaceMemberPath.problem("apps/we?rd").orElseThrow().contains("without pattern syntax"),
                () -> "problem: " + WorkspaceMemberPath.problem("apps/we?rd"));
    }

    /** The physical half of the case above, where the filesystem can hold the directory. */
    @Test
    void manifestBearingNonportableSiblingFailsActionably() throws IOException {
        Path member = workspace();
        nonportableMember("apps/we?rd", "weird");

        ZoltConfigException failure =
                assertThrows(ZoltConfigException.class, () -> loader.loadProject(member));

        assertTrue(failure.getMessage().contains("apps/we?rd"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Rename the directory"), failure.getMessage());
    }

    /**
     * Writes a member whose directory name a filesystem may refuse. Windows throws
     * {@link InvalidPathException} from {@code resolve} itself, so the assumption is taken before any
     * Zolt code is reached and the test is skipped rather than failing on a fixture it cannot build.
     */
    private void nonportableMember(String path, String name) throws IOException {
        try {
            member(path, name);
        } catch (InvalidPathException | IOException | UnsupportedOperationException exception) {
            assumeTrue(false, "path metacharacters are unavailable on this filesystem: " + exception);
        }
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
