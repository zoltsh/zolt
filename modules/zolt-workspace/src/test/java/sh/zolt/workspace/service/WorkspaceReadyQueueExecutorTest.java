package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import sh.zolt.cancel.BuildCancellation;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
        AtomicBoolean childWasInvalidated = new AtomicBoolean();

        WorkspaceReadyQueueExecutor.Result<String> result =
                new WorkspaceReadyQueueExecutor().execute(plan, 2, (member, invalidated) -> {
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
                        childWasInvalidated.set(invalidated);
                        childStarted.countDown();
                    }
                    return new WorkspaceReadyQueueExecutor.TaskResult<>(
                            member,
                            "modules/fast".equals(member));
                });

        assertEquals(3, result.resultsByMember().size());
        assertEquals(2, result.readyQueuePeak());
        assertTrue(result.schedulerIdleNanos() >= 0L);
        assertTrue(childWasInvalidated.get());
    }

    @Test
    void waitsForInterruptedSiblingAfterMemberFailure() throws Exception {
        List<String> members =
                List.of("modules/failing", "modules/sibling");
        WorkspaceBuildBatchPlanner.Plan plan =
                new WorkspaceBuildBatchPlanner().plan(
                        workspace(members, List.of()),
                        members);
        CountDownLatch siblingStarted = new CountDownLatch(1);
        CountDownLatch siblingInterrupted = new CountDownLatch(1);
        CountDownLatch allowSiblingFinish = new CountDownLatch(1);
        CountDownLatch executeFinished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                new WorkspaceReadyQueueExecutor().execute(
                        plan,
                        2,
                        (member, invalidated) -> {
                            if ("modules/failing".equals(member)) {
                                await(siblingStarted);
                                throw new BuildException("boom");
                            }
                            siblingStarted.countDown();
                            try {
                                new CountDownLatch(1).await();
                                throw new AssertionError(
                                        "Sibling build should be interrupted.");
                            } catch (InterruptedException exception) {
                                siblingInterrupted.countDown();
                                await(allowSiblingFinish);
                                return new WorkspaceReadyQueueExecutor.TaskResult<>(
                                        member,
                                        false);
                            }
                        });
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                executeFinished.countDown();
            }
        });

        try {
            assertTrue(
                    siblingInterrupted.await(2, TimeUnit.SECONDS),
                    "Sibling build was not interrupted.");
            assertFalse(
                    executeFinished.await(100, TimeUnit.MILLISECONDS),
                    "Ready-queue execution returned before its sibling stopped.");
        } finally {
            allowSiblingFinish.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(2));
        }

        assertFalse(caller.isAlive(), "Ready-queue caller did not stop.");
        assertTrue(failure.get() instanceof BuildException);
        assertEquals("boom", failure.get().getMessage());
    }

    @Test
    void cancelsRegisteredSiblingResourceBeforeWaitingForShutdown() throws Exception {
        List<String> members =
                List.of("modules/failing", "modules/sibling");
        WorkspaceBuildBatchPlanner.Plan plan =
                new WorkspaceBuildBatchPlanner().plan(
                        workspace(members, List.of()),
                        members);
        CountDownLatch siblingRegistered = new CountDownLatch(1);
        CountDownLatch siblingReleased = new CountDownLatch(1);

        BuildException failure = org.junit.jupiter.api.Assertions.assertThrows(
                BuildException.class,
                () -> new WorkspaceReadyQueueExecutor().execute(
                        plan,
                        2,
                        (member, invalidated) -> {
                            if ("modules/failing".equals(member)) {
                                await(siblingRegistered);
                                throw new BuildException("boom");
                            }
                            try (BuildCancellation.Registration ignored =
                                    BuildCancellation.onCancel(siblingReleased::countDown)) {
                                siblingRegistered.countDown();
                                await(siblingReleased);
                                return new WorkspaceReadyQueueExecutor.TaskResult<>(
                                        member,
                                        false);
                            }
                        }));

        assertEquals("boom", failure.getMessage());
        assertEquals(0L, siblingReleased.getCount());
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
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
