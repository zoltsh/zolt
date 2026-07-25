package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.resolve.ResolveException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
                tempDir,
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
