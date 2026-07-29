package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.workspace.WorkspaceConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WorkspaceBuildBatchPlannerTest {
    private final WorkspaceBuildBatchPlanner planner = new WorkspaceBuildBatchPlanner();

    @Test
    void batchesIndependentMembersTogether() {
        Workspace workspace = workspace(
                List.of("modules/core", "modules/util", "apps/api"),
                List.of());

        List<List<String>> batches = planner.batches(
                workspace,
                List.of("modules/core", "modules/util", "apps/api"));

        assertEquals(List.of(
                List.of("modules/core", "modules/util", "apps/api")), batches);
    }

    @Test
    void waitsForWorkspaceDependenciesBeforeDependents() {
        Workspace workspace = workspace(
                List.of("modules/core", "modules/util", "apps/api", "apps/worker"),
                List.of(
                        new WorkspaceProjectEdge("apps/api", "modules/core", "compile", "com.acme:core"),
                        new WorkspaceProjectEdge("apps/api", "modules/util", "compile", "com.acme:util"),
                        new WorkspaceProjectEdge("apps/worker", "modules/util", "compile", "com.acme:util")));

        List<List<String>> batches = planner.batches(
                workspace,
                List.of("modules/core", "modules/util", "apps/api", "apps/worker"));

        assertEquals(List.of(
                List.of("modules/core", "modules/util"),
                List.of("apps/api", "apps/worker")), batches);
        assertEquals(
                2,
                planner.plan(
                                workspace,
                                List.of("modules/core", "modules/util", "apps/api", "apps/worker"))
                        .dependencyDepth());
    }

    @Test
    void ignoresEdgesOutsideTheIncludedSelection() {
        Workspace workspace = workspace(
                List.of("modules/core", "modules/util", "apps/api"),
                List.of(
                        new WorkspaceProjectEdge("apps/api", "modules/core", "compile", "com.acme:core")));

        List<List<String>> batches = planner.batches(
                workspace,
                List.of("modules/core", "modules/util"));

        assertEquals(List.of(
                List.of("modules/core", "modules/util")), batches);
    }

    private static Workspace workspace(List<String> members, List<WorkspaceProjectEdge> edges) {
        List<WorkspaceMember> workspaceMembers = members.stream()
                .map(member -> new WorkspaceMember(
                        member,
                        Path.of(member),
                        config(projectName(member))))
                .toList();
        return new Workspace(
                Path.of("."),
                Path.of("zolt-workspace.toml"),
                new WorkspaceConfig("workspace", members, List.of(), Map.of(), Map.of()),
                workspaceMembers,
                edges,
                members);
    }

    private static String projectName(String member) {
        return member.substring(member.lastIndexOf('/') + 1);
    }

    private static ProjectConfig config(String name) {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(name, "0.1.0", "com.acme", "21", Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }
}
