package sh.zolt.workspace.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspacePackagePlanningTest {
    @TempDir
    private Path tempDir;

    @Test
    void packagePlanningSelectsRequestedApplicationAndBuildDependenciesWithoutPackaging() throws IOException {
        writeWorkspaceWithApiDependency();
        Files.writeString(tempDir.resolve("zolt.lock"), "version = 5\n");

        WorkspaceBuildPlan plan = new WorkspacePackageService().planPackages(
                WorkspacePlanTarget.at(tempDir.resolve("apps/api")),
                tempDir.resolve("cache"),
                new WorkspaceSelectionRequest(false, List.of("apps/api")));

        assertFalse(plan.resolvedLockfile());
        assertEquals(List.of("modules/core", "apps/api"), plan.selection().includedMembers());
        assertEquals(List.of("apps/api"), plan.selection().selectedMembers());
        assertFalse(Files.exists(tempDir.resolve("apps/api/target/api-0.1.0.jar")));
        assertFalse(Files.exists(tempDir.resolve("modules/core/target/core-0.1.0.jar")));
    }

    private void writeWorkspaceWithApiDependency() throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), """
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core", "apps/worker"]
                """);
        member("modules/core", "core", "");
        member("apps/api", "api", """
                main = "com.acme.api.Api"

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
}
