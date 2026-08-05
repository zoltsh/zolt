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

    @Test
    void readyMembersStartTheLongestDependencyPathFirst() {
        List<String> members = List.of(
                "modules/small", "modules/platform", "modules/layer1", "modules/layer2");
        Workspace workspace = workspace(
                members,
                List.of(
                        new WorkspaceProjectEdge(
                                "modules/layer1", "modules/platform", "compile", "com.acme:platform"),
                        new WorkspaceProjectEdge(
                                "modules/layer2", "modules/layer1", "compile", "com.acme:layer1")));

        WorkspaceBuildBatchPlanner.Plan plan = planner.plan(workspace, members);

        assertEquals(
                List.of("modules/platform", "modules/small"),
                drain(plan),
                "the hub every later wave waits on must start before an unrelated leaf");
        assertEquals(3, plan.criticalPathLengths().get("modules/platform"));
        assertEquals(1, plan.criticalPathLengths().get("modules/small"));
    }

    @Test
    void equalPathsAreOrderedByEstimatedDurationThenDeclarationOrder() {
        List<String> members = List.of("modules/a", "modules/b", "modules/c");
        Workspace workspace = workspace(members, List.of());
        Map<String, Integer> sourceCounts = Map.of("modules/a", 3, "modules/b", 90, "modules/c", 3);

        WorkspaceBuildBatchPlanner.Plan plan =
                planner.plan(workspace, members, member -> sourceCounts.get(member));

        assertEquals(List.of("modules/b", "modules/a", "modules/c"), drain(plan));
    }

    @Test
    void estimatesAreOnlyATieBreakerNotAnOverride() {
        List<String> members = List.of("modules/tiny-hub", "modules/big-leaf");
        Workspace workspace = workspace(
                members,
                List.of(new WorkspaceProjectEdge(
                        "modules/big-leaf", "modules/tiny-hub", "compile", "com.acme:tiny-hub")));

        WorkspaceBuildBatchPlanner.Plan plan = planner.plan(
                workspace,
                List.of("modules/tiny-hub"),
                member -> 1);

        assertEquals(List.of("modules/tiny-hub"), drain(plan));
    }

    private static List<String> drain(WorkspaceBuildBatchPlanner.Plan plan) {
        java.util.PriorityQueue<String> ready = plan.readyMembers();
        List<String> order = new java.util.ArrayList<>();
        while (!ready.isEmpty()) {
            order.add(ready.remove());
        }
        return order;
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
