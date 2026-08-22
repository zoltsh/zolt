package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.update.OutdatedScope;
import sh.zolt.workspace.WorkspaceConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DependencyUpdateScopeResolverTest {
    @TempDir
    private Path tempDir;

    private final DependencyUpdateScopeResolver resolver = new DependencyUpdateScopeResolver();

    @Test
    void standaloneProjectUsesItsOwnCanonicalManifestAndLock() throws IOException {
        Path project = writeProject(tempDir.resolve("catalog"), "catalog");

        OutdatedScope scope = resolver.reportScopes(project, 2).getFirst();

        assertEquals(project.getFileName().toString(), scope.label());
        assertEquals("zolt.toml", scope.manifestPath());
        assertEquals("zolt.lock", scope.lockfilePath());
    }

    @Test
    void malformedRootWorkspaceNeverFallsBackToStandalone() throws IOException {
        Path root = tempDir.resolve("malformed");
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "broken"

                [workspace.members]
                include = ["missing-member"]
                """);

        assertThrows(WorkspaceConfigException.class, () -> resolver.reportScopes(root, 2));
        assertThrows(WorkspaceConfigException.class, () -> resolver.catalogScopes(root, root));
    }

    @Test
    void workspaceRootReportsEveryMemberWithOneRootLock() throws IOException {
        Path root = writeWorkspace(List.of("apps/api", "modules/core"));
        writeProject(root.resolve("apps/api"), "api");
        writeProject(root.resolve("modules/core"), "core");

        List<OutdatedScope> scopes = resolver.reportScopes(root, 2);

        assertEquals(List.of("apps/api", "modules/core"), scopes.stream().map(OutdatedScope::label).toList());
        assertEquals(
                List.of("apps/api/zolt.toml", "modules/core/zolt.toml"),
                scopes.stream().map(OutdatedScope::manifestPath).toList());
        assertEquals(List.of("zolt.lock", "zolt.lock"),
                scopes.stream().map(OutdatedScope::lockfilePath).toList());
    }

    @Test
    void memberReportKeepsWorkspaceRelativePathsAndRootLock() throws IOException {
        Path root = writeWorkspace(List.of("apps/api", "modules/core"));
        Path api = writeProject(root.resolve("apps/api"), "api");
        writeProject(root.resolve("modules/core"), "core");

        List<OutdatedScope> scopes = resolver.reportScopes(api, 2);

        assertEquals(1, scopes.size());
        assertEquals("apps/api", scopes.getFirst().label());
        assertEquals("apps/api/zolt.toml", scopes.getFirst().manifestPath());
        assertEquals("zolt.lock", scopes.getFirst().lockfilePath());
    }

    @Test
    void rootMemberDotUsesRootManifest() throws IOException {
        Path root = tempDir.resolve("root-member");
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "root-member"

                [workspace.members]
                include = ["."]

                [project]
                name = "root-member"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);

        OutdatedScope scope = resolver.reportScopes(root, 2).getFirst();

        assertEquals(".", scope.label());
        assertEquals("zolt.toml", scope.manifestPath());
        assertEquals("zolt.lock", scope.lockfilePath());
    }

    @Test
    void undeclaredProjectInsideWorkspaceIsNotAnAutomationTarget() throws IOException {
        Path root = writeWorkspace(List.of("apps/api"));
        writeProject(root.resolve("apps/api"), "api");
        Path undeclared = writeProject(root.resolve("scratch/demo"), "demo");

        assertThrows(ZoltConfigException.class, () -> resolver.reportScopes(undeclared, 2));
        assertEquals(1, resolver.reportScopes(undeclared, 1).size());
    }

    @Test
    void catalogAllUsesTheConfirmedStandaloneMutationRoot() throws IOException {
        Path project = writeProject(tempDir.resolve("standalone"), "standalone");

        ResolvedUpdateScope scope = resolver.catalogScopes(project, project).getFirst();

        assertEquals(project.toAbsolutePath().normalize(), scope.mutationRoot());
        assertEquals(project.toAbsolutePath().normalize(), scope.projectDirectory());
        assertEquals(project.resolve("zolt.toml").toAbsolutePath().normalize(), scope.absoluteManifestPath());
        assertEquals(project.resolve("zolt.lock").toAbsolutePath().normalize(), scope.absoluteLockfilePath());
        assertFalse(scope.workspaceRoot());
    }

    @Test
    void catalogAllFromOneMemberIncludesEveryWorkspaceMember() throws IOException {
        Path root = writeWorkspace(List.of("apps/api", "modules/core"));
        Path api = writeProject(root.resolve("apps/api"), "api");
        writeProject(root.resolve("modules/core"), "core");

        List<ResolvedUpdateScope> scopes = resolver.catalogScopes(api, root);

        assertEquals(List.of("apps/api", "modules/core"),
                scopes.stream().map(ResolvedUpdateScope::label).toList());
        assertEquals(List.of("apps/api/zolt.toml", "modules/core/zolt.toml"),
                scopes.stream().map(ResolvedUpdateScope::manifestPath).toList());
        assertEquals(List.of(root.toAbsolutePath().normalize(), root.toAbsolutePath().normalize()),
                scopes.stream().map(ResolvedUpdateScope::mutationRoot).toList());
    }

    @Test
    void workspaceRootPlatformsAreIndependentAutomationAndCatalogScopes() throws IOException {
        Path root = writeWorkspace(List.of("apps/api"));
        Files.writeString(root.resolve("zolt.toml"), Files.readString(root.resolve("zolt.toml")) + """

                [platforms]
                "org.junit:junit-bom" = "5.10.2"
                """);
        Path api = writeProject(root.resolve("apps/api"), "api");

        List<OutdatedScope> reports = resolver.reportScopes(api, 2);
        List<OutdatedScope> schemaOneReports = resolver.reportScopes(api, 1);
        List<ResolvedUpdateScope> catalog = resolver.catalogScopes(api, root);

        assertEquals(List.of("workspace-root", "apps/api"),
                reports.stream().map(OutdatedScope::label).toList());
        assertEquals(List.of("zolt.toml", "apps/api/zolt.toml"),
                reports.stream().map(OutdatedScope::manifestPath).toList());
        // A root-owned surface has exactly one source location in both schemas; only path
        // certification differs, so schema v1 reports the root scope rather than hiding it.
        assertEquals(List.of("workspace-root", "apps/api"),
                schemaOneReports.stream().map(OutdatedScope::label).toList());
        assertEquals(List.of("zolt.toml", "apps/api/zolt.toml"),
                schemaOneReports.stream().map(OutdatedScope::manifestPath).toList());
        assertEquals(
                Map.of(
                        new DependencyCoordinate("org.junit:junit-bom"),
                        new PlatformSelector.FixedVersion("5.10.2")),
                reports.getFirst().manifest().platforms().map(AuthoredPlatforms::entries).orElseThrow());
        assertEquals(List.of("workspace-root", "apps/api"),
                catalog.stream().map(ResolvedUpdateScope::label).toList());
        assertEquals(root.toAbsolutePath().normalize(), catalog.getFirst().projectDirectory());
        assertEquals("zolt.toml", catalog.getFirst().manifestPath());
        assertTrue(catalog.getFirst().workspaceRoot());
    }

    /**
     * A root-owned platform has exactly one source location, so a member never repeats it and the
     * member scope carries only what that member authored (design §4.5 named maps).
     */
    @Test
    void memberScopesNeverRepeatRootOwnedPlatforms() throws IOException {
        Path root = writeWorkspace(List.of("apps/api"));
        Files.writeString(root.resolve("zolt.toml"), Files.readString(root.resolve("zolt.toml")) + """

                [platforms]
                "org.junit:junit-bom" = "5.10.2"
                """);
        Path api = writeProject(root.resolve("apps/api"), "api");

        OutdatedScope member = resolver.reportScopes(api, 2).get(1);

        assertEquals("apps/api", member.label());
        assertTrue(member.manifest().platforms().isEmpty());
    }

    @Test
    void rootMemberOwnsSharedManifestPlatformsWithoutDuplicateScope() throws IOException {
        Path root = tempDir.resolve("root-platform-member");
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "root-platform-member"

                [workspace.members]
                include = ["."]

                [project]
                name = "root-platform-member"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [platforms]
                "org.junit:junit-bom" = "5.10.2"
                """);

        List<OutdatedScope> reports = resolver.reportScopes(root, 2);
        List<ResolvedUpdateScope> catalog = resolver.catalogScopes(root, root);

        assertEquals(1, reports.size());
        assertEquals(1, catalog.size());
        assertEquals(".", reports.getFirst().label());
        assertEquals("zolt.toml", reports.getFirst().manifestPath());
        assertFalse(catalog.getFirst().workspaceRoot());
    }

    private Path writeWorkspace(List<String> members) throws IOException {
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root);
        String renderedMembers = members.stream()
                .map(member -> "\"" + member + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "demo"

                [workspace.members]
                include = [%s]
                """.formatted(renderedMembers));
        return root;
    }

    private static Path writeProject(Path directory, String name) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """.formatted(name));
        return directory;
    }
}
