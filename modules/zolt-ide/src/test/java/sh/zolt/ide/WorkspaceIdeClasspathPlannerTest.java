package sh.zolt.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceClasspathService;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceIdeClasspathPlannerTest {
    private final WorkspaceIdeClasspathPlanner planner =
            new WorkspaceIdeClasspathPlanner(new WorkspaceClasspathService());

    @TempDir
    private Path tempDir;

    @Test
    void returnsEmptyClasspathsWhenWorkspaceLockfileIsUnavailable() throws Exception {
        Workspace workspace = workspace(List.of(member("apps/api", "api")), List.of());

        Map<String, IdeModel.ClasspathInfo> classpaths =
                planner.classpaths(workspace, tempDir.resolve("cache"), null);

        IdeModel.ClasspathInfo apiClasspaths = classpaths.get("apps/api");
        assertEquals(List.of(), apiClasspaths.compile());
        assertEquals(List.of(), apiClasspaths.runtime());
        assertEquals(List.of(), apiClasspaths.test());
        assertEquals(List.of(), apiClasspaths.processor());
        assertEquals(List.of(), apiClasspaths.testProcessor());
        assertEquals(List.of(), apiClasspaths.quarkusDeployment());
    }

    @Test
    void addsMemberOutputsAndWorkspaceDependencyOutputs() throws Exception {
        Workspace workspace = workspace(
                List.of(member("apps/api", "api"), member("modules/core", "core")),
                List.of(new WorkspaceProjectEdge("apps/api", "modules/core", "compile", "com.acme:core")));
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(new LockPackage(
                        new PackageId("com.acme", "core"),
                        "0.1.0",
                        "workspace",
                        DependencyScope.COMPILE,
                        true,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("modules/core"),
                        Optional.of("target/classes"),
                        List.of())),
                List.of());

        Map<String, IdeModel.ClasspathInfo> classpaths =
                planner.classpaths(workspace, tempDir.resolve("cache"), lockfile);

        IdeModel.ClasspathInfo apiClasspaths = classpaths.get("apps/api");
        assertTrue(apiClasspaths.compile().contains(tempDir
                .resolve("modules/core/target/classes")
                .toAbsolutePath()
                .normalize()));
        assertEquals(
                List.of(
                        tempDir.resolve("apps/api/target/classes").toAbsolutePath().normalize(),
                        tempDir.resolve("modules/core/target/classes").toAbsolutePath().normalize()),
                apiClasspaths.runtime());
        assertEquals(
                List.of(
                        tempDir.resolve("apps/api/target/classes").toAbsolutePath().normalize(),
                        tempDir.resolve("apps/api/target/test-classes").toAbsolutePath().normalize(),
                        tempDir.resolve("modules/core/target/classes").toAbsolutePath().normalize()),
                apiClasspaths.test());
    }

    private Workspace workspace(List<WorkspaceMember> members, List<WorkspaceProjectEdge> edges) {
        return new Workspace(
                tempDir,
                tempDir.resolve("zolt.toml"),
                new WorkspaceConfig(
                        "acme-platform",
                        members.stream().map(WorkspaceMember::path).toList(),
                        List.of(),
                        Map.of(),
                        Map.of()),
                members,
                edges);
    }

    private WorkspaceMember member(String path, String name) throws Exception {
        Path directory = tempDir.resolve(path);
        Files.createDirectories(directory);
        return new WorkspaceMember(
                path,
                directory,
                ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata(name, "0.1.0", "com.acme", "21", Optional.empty()),
                        ProjectConfig.defaultRepositories(),
                        Map.of(),
                        Map.of(),
                        BuildSettings.defaults()));
    }
}
