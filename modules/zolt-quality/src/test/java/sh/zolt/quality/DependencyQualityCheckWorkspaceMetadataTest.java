package sh.zolt.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.policy.DependencyPolicyReportService;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;
import sh.zolt.workspace.service.WorkspaceSelection;

final class DependencyQualityCheckWorkspaceMetadataTest extends QualityCheckServiceTestSupport {
    private final DependencyQualityCheck check = new DependencyQualityCheck(
            new ZoltLockfileReader(),
            new DependencyPolicyReportService());
    private final WorkspaceQualityProjectionService projectionService =
            new WorkspaceQualityProjectionService(new ZoltLockfileReader());

    @TempDir
    private Path tempDir;

    @Test
    void workspaceProjectionRequiresVersionFiveLockBeforeMemberChecks() throws IOException {
        WorkspaceFixture fixture = workspaceFixture("");
        Files.writeString(tempDir.resolve("zolt.lock"), "version = 4\n");

        WorkspaceQualityProjectionException failure = assertThrows(
                WorkspaceQualityProjectionException.class,
                () -> project(workspace(fixture.members(), List.of()), fixture));

        assertTrue(failure.getMessage().contains("version 4"));
        assertTrue(failure.getMessage().contains("optional-boundary evidence"));
        assertTrue(failure.nextStep().contains("zolt resolve --workspace"));
    }

    @Test
    void workspaceProjectionReportsMalformedLockfileWithWorkspaceResolveAction() throws IOException {
        WorkspaceFixture fixture = workspaceFixture("");
        Files.writeString(tempDir.resolve("zolt.lock"), """
                version = 5

                [[package]]
                id = 42
                """);

        WorkspaceQualityProjectionException failure = assertThrows(
                WorkspaceQualityProjectionException.class,
                () -> project(workspace(fixture.members(), List.of()), fixture));

        assertTrue(failure.getMessage().contains("Invalid value type in zolt.lock"));
        assertEquals("Run `zolt resolve --workspace`.", failure.nextStep());
    }

    @Test
    void workspaceMetadataMatchesExactMemberVariantAndScope() throws IOException {
        WorkspaceFixture fixture = workspaceFixture("""

                [dependencies]
                "com.example:helper" = { version = "1.0.0", classifier = "linux" }
                """);
        writeWorkspaceLockfile(
                packageEntry(
                        "com.example:helper",
                        "1.0.0",
                        "compile",
                        true,
                        "com/example/helper/1.0.0/helper-1.0.0.jar",
                        "members = [\"apps/api\"]")
                        + packageEntry(
                                "com.example:helper",
                                "1.0.0",
                                "compile",
                                true,
                                "com/example/helper/1.0.0/helper-1.0.0-linux.jar",
                                "members = [\"apps/api\"]"));

        Workspace workspace = workspace(fixture.members(), List.of());
        QualityCheckResult result =
                check.checkWorkspaceMetadata(workspace, selection(), project(workspace, fixture))
                        .getFirst();

        assertEquals(QualityCheckStatus.PASSED, result.status());
        assertEquals(Optional.of("apps/api"), result.member());
        assertTrue(result.message().contains("variant `jar|linux`"));
        assertTrue(result.message().contains("scope `compile`"));
    }

    @Test
    void workspaceMetadataAcceptsOptionalApiEdgeAndOptionalLockEvidence() throws IOException {
        WorkspaceFixture fixture = workspaceFixture("""

                [api.dependencies]
                "com.example:core" = { workspace = "modules/core", optional = true }
                """);
        writeWorkspaceLockfile(workspacePackageEntry(""));
        Workspace workspace = workspace(
                fixture.members(),
                List.of(new WorkspaceProjectEdge(
                        "apps/api",
                        "modules/core",
                        "compile",
                        "com.example:core",
                        true,
                        true)));

        List<QualityCheckResult> results =
                check.checkWorkspaceMetadata(workspace, selection(), project(workspace, fixture));

        assertEquals(1, results.size());
        assertEquals(QualityCheckStatus.PASSED, results.getFirst().status());
        assertEquals(Optional.of("apps/api"), results.getFirst().member());
        assertTrue(results.getFirst().message().contains("Optional workspace API dependency"));
        assertTrue(results.getFirst().message().contains("without propagating across workspace classpaths"));
    }

    @Test
    void workspaceMetadataRejectsMissingExportedByForRequiredApiEdge() throws IOException {
        WorkspaceFixture fixture = workspaceFixture("""

                [api.dependencies]
                "com.example:core" = { workspace = "modules/core" }
                """);
        writeWorkspaceLockfile(workspacePackageEntry(""));
        Workspace workspace = workspace(
                fixture.members(),
                List.of(new WorkspaceProjectEdge(
                        "apps/api",
                        "modules/core",
                        "compile",
                        "com.example:core",
                        true,
                        false)));

        QualityCheckResult result =
                check.checkWorkspaceMetadata(workspace, selection(), project(workspace, fixture))
                        .getFirst();

        assertEquals(QualityCheckStatus.FAILED, result.status(), result.toString());
        assertEquals(
                "Workspace API dependency `com.example:core` is missing exportedBy ownership in zolt.lock.",
                result.message());
    }

    private WorkspaceQualityProjection project(
            Workspace workspace,
            WorkspaceFixture fixture) {
        return projectionService.project(workspace, selection(), fixture.membersByPath());
    }

    private static WorkspaceSelection selection() {
        return new WorkspaceSelection(List.of("apps/api"), List.of("apps/api"));
    }

    private static String packageEntry(
            String coordinate,
            String version,
            String scope,
            boolean direct,
            String jar,
            String extra) {
        return """

                [[package]]
                id = "%s"
                version = "%s"
                source = "maven-central"
                scope = "%s"
                direct = %s
                jar = "%s"
                %s
                dependencies = []
                """.formatted(coordinate, version, scope, direct, jar, extra);
    }

    private static String workspacePackageEntry(String extra) {
        return """

                [[package]]
                id = "com.example:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/core"
                workspaceOutput = "target/classes"
                members = ["apps/api"]
                dependencies = []
                %s
                """.formatted(extra);
    }

    private WorkspaceFixture workspaceFixture(String apiBody) throws IOException {
        Path apiDir = tempDir.resolve("apps/api");
        Path coreDir = tempDir.resolve("modules/core");
        ProjectConfig api = parseProject(apiDir, apiBody);
        ProjectConfig core = parseProject(coreDir, "");
        List<WorkspaceMember> members = List.of(
                new WorkspaceMember("apps/api", apiDir, api),
                new WorkspaceMember("modules/core", coreDir, core));
        return new WorkspaceFixture(members, Map.of(
                "apps/api", members.get(0),
                "modules/core", members.get(1)));
    }

    private Workspace workspace(List<WorkspaceMember> members, List<WorkspaceProjectEdge> edges) {
        return new Workspace(
                tempDir,
                tempDir.resolve("zolt-workspace.toml"),
                new WorkspaceConfig(
                        "demo",
                        List.of("apps/api", "modules/core"),
                        List.of(),
                        Map.of(),
                        Map.of()),
                members,
                edges);
    }

    private void writeWorkspaceLockfile(String packages) throws IOException {
        Files.writeString(tempDir.resolve("zolt.lock"), "version = 5\n" + packages);
    }

    private record WorkspaceFixture(
            List<WorkspaceMember> members,
            Map<String, WorkspaceMember> membersByPath) {
    }
}
