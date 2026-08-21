package sh.zolt.workspace.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.workspace.WorkspaceConfigException;

final class ManifestWorkspaceDiscoveryTest {
    private final ManifestWorkspaceDiscovery discovery = new ManifestWorkspaceDiscovery();

    @TempDir
    private Path tempDir;

    @Test
    void discoversPatternsComposesMembersAndRetainsEvidence() throws IOException {
        root("""
                [workspace]
                name = "platform"

                [workspace.members]
                default = ["apps/api"]
                include = ["apps/*", "modules/*"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21
                """);
        member("apps/api", "api", "");
        member("modules/core", "core", "");
        Path nested = tempDir.resolve("apps/api/src/main/java");
        Files.createDirectories(nested);

        DiscoveredWorkspace workspace = discovery.discover(nested).orElseThrow();

        assertEquals(tempDir.toAbsolutePath().normalize(), workspace.root());
        assertEquals(
                List.of("apps/api", "modules/core"),
                workspace.members().keySet().stream().map(WorkspaceMemberPath::value).toList());
        assertEquals(
                List.of("apps/*"),
                workspace.members().get(path("apps/api")).matchedBy().stream()
                        .map(Object::toString)
                        .toList());
        assertEquals("apps/api/zolt.toml", workspace.members().get(path("apps/api")).manifestPath());
        assertEquals(
                WorkspaceMemberSelection.Source.EXPLICIT_DEFAULT,
                workspace.selection().source());
        assertEquals(List.of(path("apps/api")), workspace.selection().members());
        assertEquals(
                "com.example",
                workspace.effective().members().get(path("apps/api"))
                        .project().identity().group().value().value());
    }

    @Test
    void excludesCandidatesBeforeReadingTheirInvalidManifests() throws IOException {
        root("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]
                exclude = ["apps/experimental"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21
                """);
        member("apps/api", "api", "");
        Path excluded = tempDir.resolve("apps/experimental");
        Files.createDirectories(excluded);
        Files.writeString(excluded.resolve("zolt.toml"), "not valid [toml");

        DiscoveredWorkspace workspace = discovery.load(tempDir);

        assertEquals(List.of(path("apps/api")), workspace.members().keySet().stream().toList());
        assertEquals(
                WorkspaceMemberSelection.Source.IMPLICIT_ALL,
                workspace.selection().source());
        assertEquals(List.of(path("apps/api")), workspace.selection().members());
        assertTrue(workspace.inputs().content(excluded.resolve("zolt.toml")).isEmpty());
        assertFalse(workspace.inputs().digestsRelativeTo(tempDir)
                .containsKey("apps/experimental/zolt.toml"));
        assertTrue(workspace.staleExclusions().isEmpty());

        Files.writeString(excluded.resolve("zolt.toml"), "still not valid [toml");
        workspace.inputs().requireCurrent();
    }

    @Test
    void ignoresPatternDirectoriesWithoutManifestButRequiresEveryIncludeToContribute() throws IOException {
        root("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*", "modules/*"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21
                """);
        member("apps/api", "api", "");
        Files.createDirectories(tempDir.resolve("apps/docs"));
        Files.createDirectories(tempDir.resolve("modules/readme"));

        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> discovery.load(tempDir));

        assertTrue(exception.getMessage().contains("Workspace include `modules/*`"));
    }

    @Test
    void rejectsExactMissingManifestAndExactExclusionContradiction() throws IOException {
        root("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/api"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21
                """);
        Files.createDirectories(tempDir.resolve("apps/api"));

        WorkspaceConfigException missing = assertThrows(
                WorkspaceConfigException.class,
                () -> discovery.load(tempDir));
        assertTrue(missing.getMessage().contains("must contain zolt.toml"));

        root("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/api"]
                exclude = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21
                """);
        WorkspaceConfigException contradiction = assertThrows(
                WorkspaceConfigException.class,
                () -> discovery.load(tempDir));
        assertTrue(contradiction.getMessage().contains("Exact workspace include `apps/api`"));
        assertTrue(contradiction.getMessage().contains("workspace exclude `apps/*`"));
    }

    @Test
    void rejectsAnExactIncludeThatDoesNotResolveToADirectory() throws IOException {
        root("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/missing"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21
                """);

        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> discovery.load(tempDir));

        assertTrue(exception.getMessage().contains("Exact workspace include `apps/missing`"));
        assertTrue(exception.getMessage().contains("must resolve to a directory"));
    }

    @Test
    void supportsDotMemberAndReusesTheRootDocument() throws IOException {
        root("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["."]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21

                [project]
                name = "root"
                """);

        DiscoveredWorkspace workspace = discovery.load(tempDir);
        DiscoveredWorkspaceMember root = workspace.members().get(path("."));

        assertSame(workspace.rootDocument(), root.document());
        assertSame(workspace.rootDocument().authored(), root.document().authored());
        assertEquals("zolt.toml", root.manifestPath());
    }

    @Test
    void reportsStaleExclusionsWithoutRejectingTheWorkspace() throws IOException {
        root("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]
                exclude = ["modules/retired"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21
                """);
        member("apps/api", "api", "");

        assertEquals(
                List.of("modules/retired"),
                discovery.load(tempDir).staleExclusions().stream()
                        .map(Object::toString)
                        .toList());
    }

    @Test
    void finalDiscoveryDoesNotRecognizeLegacyWorkspaceFile() throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), """
                [workspace]
                name = "legacy"
                members = ["apps/api"]
                """);

        assertFalse(discovery.discover(tempDir).isPresent());
        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> discovery.load(tempDir));
        assertTrue(exception.getMessage().contains("final workspace manifest"));
    }

    @Test
    void rootLookupDoesNotReadOrComposeMembersBeforeTheMutationLock() throws IOException {
        root("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21
                """);
        Path invalid = tempDir.resolve("apps/api/zolt.toml");
        Files.createDirectories(invalid.getParent());
        Files.writeString(invalid, "not valid [toml");

        assertEquals(tempDir.toAbsolutePath().normalize(),
                discovery.discoverRoot(tempDir).orElseThrow());
        assertThrows(WorkspaceConfigException.class, () -> discovery.load(tempDir));
    }

    @Test
    void publicAggregateRejectsForgedSelectionDirectoryAndPatternEvidence() throws IOException {
        root("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21
                """);
        member("apps/api", "api", "");
        DiscoveredWorkspace valid = discovery.load(tempDir);
        WorkspaceMemberSelection forgedSelection = new WorkspaceMemberSelection(
                WorkspaceMemberSelection.Source.EXPLICIT_DEFAULT, List.of(path("apps/api")));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveredWorkspace(
                valid.root(), valid.rootDocument(), valid.effective(), valid.members(),
                forgedSelection, valid.staleExclusions(), valid.inputs()));

        DiscoveredWorkspaceMember member = valid.members().get(path("apps/api"));
        LinkedHashMap<WorkspaceMemberPath, DiscoveredWorkspaceMember> forged =
                new LinkedHashMap<>(valid.members());
        forged.put(path("apps/api"), new DiscoveredWorkspaceMember(
                path("apps/api"), tempDir.resolve("elsewhere"), member.document(), member.matchedBy()));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveredWorkspace(
                valid.root(), valid.rootDocument(), valid.effective(), forged,
                valid.selection(), valid.staleExclusions(), valid.inputs()));

        forged.put(path("apps/api"), new DiscoveredWorkspaceMember(
                path("apps/api"), member.directory(), member.document(),
                List.of(new sh.zolt.manifest.WorkspaceMemberPattern("modules/*"))));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveredWorkspace(
                valid.root(), valid.rootDocument(), valid.effective(), forged,
                valid.selection(), valid.staleExclusions(), valid.inputs()));
    }

    @Test
    void acceptsActualDirectoryNamesThatNormalizeToTheCanonicalMemberPath() throws IOException {
        root("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["modules/café"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21
                """);
        member("modules/café", "coffee", "");

        DiscoveredWorkspace workspace = discovery.load(tempDir);

        assertEquals(List.of(path("modules/café")), workspace.members().keySet().stream().toList());
        assertEquals(
                path("modules/café"),
                new WorkspaceMemberPath(tempDir.relativize(
                                workspace.members().get(path("modules/café")).directory())
                        .toString()
                        .replace('\\', '/')));
    }

    private void root(String source) throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), source);
    }

    private void member(String path, String name, String extra) throws IOException {
        Path directory = tempDir.resolve(path);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "%s"
                %s""".formatted(name, extra));
    }

    private static WorkspaceMemberPath path(String value) {
        return new WorkspaceMemberPath(value);
    }
}
