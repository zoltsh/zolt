package sh.zolt.workspace.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.workspace.WorkspaceConfigException;

/**
 * Design §6.3: only a complete {@code *} segment is a wildcard. Every other portable character —
 * {@code [}, {@code ]}, {@code &#123;}, {@code &#125;} included — is a literal path character, so an
 * unrelated sibling directory can never brick discovery of a wildcard workspace.
 *
 * <p><strong>Portability.</strong> The cases that carry the load use brackets and braces, which every
 * supported filesystem holds. {@code ?} and {@code *} inside a segment are rejected by Windows when
 * the {@link Path} is constructed, before discovery runs at all, so those fixtures are skipped where
 * the filesystem cannot hold them and the grammar's rejection is asserted from the model instead — on
 * every platform.
 */
final class WorkspaceMetacharacterDirectoryTest {
    private final ManifestWorkspaceLoader loader = new ManifestWorkspaceLoader();

    @TempDir
    private Path tempDir;

    @Test
    void metacharDirectoryWithoutManifestDoesNotAffectWorkspace() throws IOException {
        workspace("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """);
        member("apps/api", "api");
        Files.createDirectories(tempDir.resolve("apps/notes[draft]"));
        Files.createDirectories(tempDir.resolve("apps/{scratch}"));

        assertEquals(List.of("apps/api"), memberPaths());
    }

    @Test
    void bracketDirectoryCanBeExcludedExactly() throws IOException {
        workspace("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]
                exclude = ["apps/notes[draft]"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """);
        member("apps/api", "api");
        member("apps/notes[draft]", "notes");

        assertEquals(List.of("apps/api"), memberPaths());
    }

    @Test
    void bracketDirectoryWithAManifestIsAnOrdinaryMember() throws IOException {
        workspace("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """);
        member("apps/api", "api");
        member("apps/notes[draft]", "notes");

        assertEquals(List.of("apps/api", "apps/notes[draft]"), memberPaths());
    }

    /**
     * Platform-independent: the expander's grammar refuses to give {@code ?} or {@code *} inside a
     * segment a member identity, on every platform — including the ones whose filesystem could never
     * produce such a directory for the physical cases below.
     */
    @Test
    void patternSyntaxCannotCarryMemberIdentityOnAnyPlatform() {
        assertEquals(Optional.empty(), WorkspaceMemberPath.problem("apps/notes[draft]"));
        assertEquals(Optional.empty(), WorkspaceMemberPath.problem("modules/{scratch}"));
        assertTrue(
                WorkspaceMemberPath.problem("apps/we?rd").orElseThrow().contains("without pattern syntax"),
                () -> "problem: " + WorkspaceMemberPath.problem("apps/we?rd"));
        assertTrue(
                WorkspaceMemberPath.problem("modules/star*name").orElseThrow()
                        .contains("without pattern syntax"),
                () -> "problem: " + WorkspaceMemberPath.problem("modules/star*name"));
    }

    /** The physical half of the case above, where the filesystem can hold the directory. */
    @Test
    void manifestBearingNonportablePathFailsActionably() throws IOException {
        workspace("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """);
        member("apps/api", "api");
        nonportableMember("apps/we?rd", "weird");

        WorkspaceConfigException failure =
                assertThrows(WorkspaceConfigException.class, this::memberPaths);

        assertTrue(failure.getMessage().contains("apps/we?rd"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Rename"), failure.getMessage());
    }

    @Test
    void wildcardSiblingCannotBrickWorkspaceDiscovery() throws IOException {
        wildcardWorkspace();
        Files.createDirectories(tempDir.resolve("apps/notes[draft]/src"));
        Files.createDirectories(tempDir.resolve("modules/{scratch}"));

        assertEquals(List.of("apps/api", "modules/core"), memberPaths());
    }

    /**
     * The same statement for the two characters that ARE pattern syntax, as unrelated directories with
     * no manifest. Split out rather than folded into the case above so a filesystem that cannot hold
     * these names skips only this, leaving the portable sibling guarantee asserted everywhere.
     */
    @Test
    void patternSyntaxSiblingWithoutAManifestCannotBrickWorkspaceDiscovery() throws IOException {
        wildcardWorkspace();
        nonportableDirectory("modules/we?rd");
        nonportableDirectory("modules/star*name");

        assertEquals(List.of("apps/api", "modules/core"), memberPaths());
    }

    private void wildcardWorkspace() throws IOException {
        workspace("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*", "modules/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """);
        member("apps/api", "api");
        member("modules/core", "core");
    }

    /**
     * Creates a directory whose name a filesystem may refuse. Windows throws
     * {@link InvalidPathException} from {@code resolve} itself, so the assumption is taken before
     * discovery runs and the test is skipped rather than failing on a fixture it cannot build.
     */
    private void nonportableDirectory(String path) throws IOException {
        try {
            Files.createDirectories(tempDir.resolve(path));
        } catch (InvalidPathException | IOException | UnsupportedOperationException exception) {
            assumeTrue(false, "path metacharacters are unavailable on this filesystem: " + exception);
        }
    }

    /** As {@link #nonportableDirectory(String)}, for a directory that also carries a manifest. */
    private void nonportableMember(String path, String name) throws IOException {
        try {
            member(path, name);
        } catch (InvalidPathException | IOException | UnsupportedOperationException exception) {
            assumeTrue(false, "path metacharacters are unavailable on this filesystem: " + exception);
        }
    }

    private List<String> memberPaths() {
        return loader.load(tempDir).members().stream()
                .map(member -> member.path())
                .toList();
    }

    private void workspace(String source) throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), source);
    }

    private void member(String path, String name) throws IOException {
        Path directory = tempDir.resolve(path);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "%s"
                """.formatted(name));
    }
}
