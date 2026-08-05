package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.build.BuildException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.resolve.ResolveException;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import sh.zolt.workspace.test.WorkspaceTestService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspacePlanningServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void buildPlanningSelectsRequestedMemberAndDependenciesWithoutExecutingBuild() throws IOException {
        writeWorkspaceWithApiDependency();
        writeLockfile();

        WorkspaceBuildPlan plan = new WorkspaceBuildService().planBuild(
                tempDir.resolve("apps/api"),
                tempDir.resolve("cache"),
                true,
                new WorkspaceSelectionRequest(false, List.of("apps/api")));

        assertFalse(plan.resolvedLockfile());
        assertEquals(tempDir.toAbsolutePath().normalize(), plan.workspace().root());
        assertEquals(List.of("modules/core", "apps/api"), plan.selection().includedMembers());
        assertEquals(List.of("apps/api"), plan.selection().selectedMembers());
        assertEquals(0, plan.lockfile().packages().size());
        assertFalse(Files.exists(tempDir.resolve("modules/core/target")));
        assertFalse(Files.exists(tempDir.resolve("apps/api/target")));
    }

    @Test
    void testPlanningUsesWorkspaceSelectionWithoutExecutingTestInputs() throws IOException {
        writeWorkspaceWithApiDependency();
        writeLockfile();

        WorkspaceBuildPlan plan = new WorkspaceTestService().planTests(
                WorkspacePlanTarget.at(tempDir),
                tempDir.resolve("cache"),
                new WorkspaceSelectionRequest(false, List.of("apps/api")));

        assertEquals(List.of("modules/core", "apps/api"), plan.selection().includedMembers());
        assertEquals(List.of("apps/api"), plan.selection().selectedMembers());
        assertFalse(plan.resolvedLockfile());
        assertFalse(Files.exists(tempDir.resolve("apps/api/target/test-classes")));
    }

    @Test
    void buildPlanningReportsMissingWorkspaceConfigWithNextStep() {
        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> new WorkspaceBuildService().planBuild(
                        tempDir,
                        tempDir.resolve("cache"),
                        false,
                        WorkspaceSelectionRequest.defaults()));

        assertEquals(
                "Could not find workspace config. Run `zolt build --workspace` from a workspace directory or add zolt.toml with [workspace].",
                exception.getMessage());
        assertTrue(exception.actionableError().remediation().contains("Run `zolt build --workspace`"));
    }

    @Test
    void detachedBuildPlanRefusesLockCommittedByLaterWorkspaceResolve() throws Exception {
        writeWorkspaceWithApiDependency();
        writeLockfile();
        WorkspaceBuildService service = new WorkspaceBuildService();
        WorkspaceBuildPlan plan = service.planBuild(
                tempDir,
                tempDir.resolve("cache"),
                true,
                WorkspaceSelectionRequest.defaults());
        String plannedLockfile = Files.readString(tempDir.resolve("zolt.lock"));
        AtomicReference<Throwable> resolveFailure = new AtomicReference<>();
        Thread resolver = Thread.ofPlatform().start(() -> {
            try {
                new WorkspaceResolveService().resolve(
                        tempDir,
                        tempDir.resolve("cache"),
                        false,
                        true);
            } catch (Throwable throwable) {
                resolveFailure.set(throwable);
            }
        });
        resolver.join();

        assertEquals(null, resolveFailure.get());
        assertFalse(plannedLockfile.equals(Files.readString(tempDir.resolve("zolt.lock"))));
        BuildException exception = assertThrows(
                BuildException.class,
                () -> service.build(plan, tempDir.resolve("cache")));

        assertTrue(exception.getMessage().contains("changed after planning"));
        assertFalse(Files.exists(tempDir.resolve(".zolt/workspace-state-v1")));
    }

    @Test
    void planningNeverBlessesWorkspaceParsedFromDifferentConfigBytes() throws Exception {
        writeWorkspaceWithApiDependency();
        writeLockfile();
        CountDownLatch discovered = new CountDownLatch(1);
        CountDownLatch changed = new CountDownLatch(1);
        WorkspaceDiscoveryService discovery = new WorkspaceDiscoveryService();
        WorkspaceBuildPlanner planner = new WorkspaceBuildPlanner(
                start -> {
                    var workspace = discovery.discover(start);
                    discovered.countDown();
                    await(changed);
                    return workspace;
                },
                new WorkspaceResolveService(),
                new ZoltLockfileReader(),
                new WorkspaceMemberSelector());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread planning = Thread.ofPlatform().start(() -> {
            try {
                planner.plan(
                        tempDir,
                        tempDir.resolve("cache"),
                        true,
                        WorkspaceSelectionRequest.defaults(),
                        false);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        discovered.await();
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), """
                [workspace]
                name = "replacement"
                members = ["apps/worker"]
                """);
        changed.countDown();
        planning.join();

        assertTrue(failure.get() instanceof BuildException);
        assertTrue(failure.get().getMessage().contains("changed after planning"));
    }

    @Test
    void missingLockResolveNeverMixesCapturedWorkspaceWithNewerMemberList() throws Exception {
        writeWorkspaceWithApiDependency();
        WorkspaceDiscoveryService discovery = new WorkspaceDiscoveryService();
        WorkspaceBuildPlanner planner = new WorkspaceBuildPlanner(
                start -> {
                    var captured = discovery.discover(start);
                    try {
                        Files.writeString(tempDir.resolve("zolt-workspace.toml"), """
                                [workspace]
                                name = "replacement"
                                members = ["apps/worker"]
                                """);
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                    return captured;
                },
                new WorkspaceResolveService(),
                new ZoltLockfileReader(),
                new WorkspaceMemberSelector());

        BuildException exception = assertThrows(
                BuildException.class,
                () -> planner.plan(
                        tempDir,
                        tempDir.resolve("cache"),
                        true,
                        WorkspaceSelectionRequest.defaults(),
                        false));

        assertTrue(exception.getMessage().contains("changed after planning"));
        assertFalse(Files.exists(tempDir.resolve("zolt.lock")));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating workspace planning test.", exception);
        }
    }

    private void writeWorkspaceWithApiDependency() throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), """
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core", "apps/worker"]
                """);
        member("modules/core", "core", "");
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        member("apps/worker", "worker", "");
    }

    private void member(String path, String name, String extraToml) throws IOException {
        Path member = tempDir.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = "21"
                %s""".formatted(name, extraToml));
    }

    private void writeLockfile() throws IOException {
        Files.writeString(tempDir.resolve("zolt.lock"), "version = 5\n");
    }
}
