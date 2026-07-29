package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.workspace.WorkspaceConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class WorkspaceReadyQueueExecutorTest {
    @Test
    void startsDependentAsSoonAsItsOwnDependencyCompletes() {
        List<String> members = List.of("modules/slow", "modules/fast", "apps/fast-child");
        Workspace workspace = workspace(
                members,
                List.of(new WorkspaceProjectEdge(
                        "apps/fast-child",
                        "modules/fast",
                        "compile",
                        "com.acme:fast")));
        WorkspaceBuildBatchPlanner.Plan plan =
                new WorkspaceBuildBatchPlanner().plan(workspace, members);
        CountDownLatch childStarted = new CountDownLatch(1);

        WorkspaceReadyQueueExecutor.Result<String> result =
                new WorkspaceReadyQueueExecutor().execute(plan, 2, member -> {
                    if ("modules/slow".equals(member)) {
                        try {
                            assertTrue(
                                    childStarted.await(2, TimeUnit.SECONDS),
                                    "The dependent stayed behind an unrelated slow member.");
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(exception);
                        }
                    }
                    if ("apps/fast-child".equals(member)) {
                        childStarted.countDown();
                    }
                    return member;
                });

        assertEquals(3, result.resultsByMember().size());
        assertEquals(2, result.readyQueuePeak());
        assertTrue(result.schedulerIdleNanos() >= 0L);
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
