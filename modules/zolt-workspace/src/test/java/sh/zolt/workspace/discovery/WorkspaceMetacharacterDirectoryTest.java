package sh.zolt.workspace.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.workspace.WorkspaceConfigException;

/**
 * Design §6.3: only a complete {@code *} segment is a wildcard. Every other portable character —
 * {@code [}, {@code ]}, {@code &#123;}, {@code &#125;} included — is a literal path character, so an
 * unrelated sibling directory can never brick discovery of a wildcard workspace.
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
        member("apps/we?rd", "weird");

        WorkspaceConfigException failure =
                assertThrows(WorkspaceConfigException.class, this::memberPaths);

        assertTrue(failure.getMessage().contains("apps/we?rd"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Rename"), failure.getMessage());
    }

    @Test
    void wildcardSiblingCannotBrickWorkspaceDiscovery() throws IOException {
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
        Files.createDirectories(tempDir.resolve("apps/notes[draft]/src"));
        Files.createDirectories(tempDir.resolve("modules/we?rd"));
        Files.createDirectories(tempDir.resolve("modules/star*name"));

        assertEquals(List.of("apps/api", "modules/core"), memberPaths());
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
