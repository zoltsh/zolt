package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.toml.ZoltConfigException;
import sh.zolt.update.OutdatedScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    void retainedEmptyWorkspaceDomainRemainsStandalone() throws IOException {
        Path project = writeProject(tempDir.resolve("retained"), "retained");
        Files.writeString(project.resolve("zolt.toml"), Files.readString(project.resolve("zolt.toml")) + """

                [workspace]
                name = "retained"
                members = []
                """);

        OutdatedScope report = resolver.reportScopes(project, 2).getFirst();
        ResolvedUpdateScope catalog = resolver.catalogScopes(project, project).getFirst();

        assertEquals("zolt.toml", report.manifestPath());
        assertEquals(project.toAbsolutePath().normalize(), catalog.projectDirectory());
    }

    @Test
    void workspaceRootReportsEveryMemberWithOneRootLock() throws IOException {
        Path root = writeWorkspace("zolt.toml", List.of("apps/api", "modules/core"));
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
        Path root = writeWorkspace("zolt.toml", List.of("apps/api", "modules/core"));
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
                members = ["."]

                [project]
                name = "root-member"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """);

        OutdatedScope scope = resolver.reportScopes(root, 2).getFirst();

        assertEquals(".", scope.label());
        assertEquals("zolt.toml", scope.manifestPath());
        assertEquals("zolt.lock", scope.lockfilePath());
    }

    @Test
    void legacyWorkspaceUsesTheSameCanonicalPaths() throws IOException {
        Path root = writeWorkspace("zolt-workspace.toml", List.of("apps/api"));
        Path api = writeProject(root.resolve("apps/api"), "api");

        OutdatedScope scope = resolver.reportScopes(api, 2).getFirst();

        assertEquals("apps/api/zolt.toml", scope.manifestPath());
        assertEquals("zolt.lock", scope.lockfilePath());
    }

    @Test
    void undeclaredProjectInsideWorkspaceIsNotAnAutomationTarget() throws IOException {
        Path root = writeWorkspace("zolt.toml", List.of("apps/api"));
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
    }

    @Test
    void catalogAllFromOneMemberIncludesEveryWorkspaceMember() throws IOException {
        Path root = writeWorkspace("zolt.toml", List.of("apps/api", "modules/core"));
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

    private Path writeWorkspace(String filename, List<String> members) throws IOException {
        Path root = tempDir.resolve(filename.startsWith("zolt-workspace") ? "legacy" : "modern");
        Files.createDirectories(root);
        String renderedMembers = members.stream()
                .map(member -> "\"" + member + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        Files.writeString(root.resolve(filename), """
                [workspace]
                name = "demo"
                members = [%s]
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
                java = "21"
                """.formatted(name));
        return directory;
    }
}
