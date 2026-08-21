package sh.zolt.quality.execution;

import sh.zolt.publish.ManifestPublishSettingsLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.publish.PublishDryRunService;
import sh.zolt.publish.PublishSettingsReader;
import sh.zolt.quality.QualityCheckContext;
import sh.zolt.quality.QualityCheckRequest;
import sh.zolt.quality.QualityCheckResult;
import sh.zolt.quality.QualityCheckService;
import sh.zolt.quality.QualityCheckStatus;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceSelection;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QualityExecutionContextRunnerTest {
    private final ManifestProjectConfigLoader manifestLoader = new ManifestProjectConfigLoader();
    private final QualityExecutionContextRunner runner = QualityExecutionContextRunner.create(
            new ZoltLockfileReader(),
            new ManifestPublishSettingsLoader(),
            Map.<String, String>of()::get,
            new PublishDryRunService());

    @TempDir
    private Path tempDir;

    @Test
    void workspaceLocalContextStopsBeforeMemberPreflights() throws IOException {
        Path workspaceDir = tempDir.resolve("local-workspace");
        WorkspaceMember api = member(workspaceDir, "apps/api", """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.private]
                url = "https://repo.example.test/maven"
                credentials = "missing-creds"

                [credentials.missing-creds]
                usernameEnv = "MISSING_USER"
                passwordEnv = "MISSING_TOKEN"
                """);
        Workspace workspace = workspace(workspaceDir, List.of(api));

        List<QualityCheckResult> results = runner.checkWorkspace(
                request(workspaceDir, QualityCheckContext.LOCAL, true, Path.of("reports"), Path.of("coverage")),
                workspace,
                new WorkspaceSelection(List.of("apps/api"), List.of("apps/api")),
                Map.of("apps/api", api));

        assertEquals(1, results.size());
        QualityCheckResult result = results.getFirst();
        assertEquals(QualityCheckService.EXECUTION_CONTEXT, result.id());
        assertEquals(QualityCheckStatus.PASSED, result.status());
        assertEquals(Optional.empty(), result.member());
        assertEquals("local", result.subject());
        assertEquals(
                "Local context policy is active. Policy source: built-in local context. Local overlays are allowed, zolt.lock is not required before editing, and CI/release preflights remain explicit.",
                result.message());
    }

    @Test
    void workspaceCiRunsMemberPreflightsForIncludedMembersAndEvidenceForSelectedMembers() throws IOException {
        Path workspaceDir = tempDir.resolve("ci-workspace");
        Files.createDirectories(workspaceDir);
        Files.writeString(workspaceDir.resolve("zolt.lock"), "version = 7\n");
        WorkspaceMember core = member(workspaceDir, "modules/core", """
                [project]
                name = "core"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.private]
                url = "https://repo.example.test/maven"
                credentials = "company-creds"

                [credentials.company-creds]
                usernameEnv = "COMPANY_USER"
                passwordEnv = "COMPANY_TOKEN"
                """);
        WorkspaceMember api = member(workspaceDir, "apps/api", """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        Workspace workspace = workspace(workspaceDir, List.of(core, api));

        List<QualityCheckResult> results = runner.checkWorkspace(
                request(workspaceDir, QualityCheckContext.CI, true, Path.of("target/test-reports"), Path.of("target/coverage")),
                workspace,
                new WorkspaceSelection(List.of("modules/core", "apps/api"), List.of("apps/api")),
                Map.of(
                        "modules/core", core,
                        "apps/api", api));

        List<String> summaries = results.stream()
                .map(result -> result.member().orElse("<root>") + "|" + result.subject() + "|" + result.message())
                .toList();
        assertTrue(summaries.contains("<root>|ci|CI context policy is active. Policy source: built-in ci context. Locked model checks, generated-source checks, package diagnostics, local overlay rejection, and credential preflight are enabled."));
        assertTrue(summaries.contains("modules/core|[repositoryCredentials.company-creds]|CI context requires environment variables COMPANY_USER, COMPANY_TOKEN for repository `private` credentials `company-creds` before resolve/build work starts."));
        // The publish dry-run runs once as a family preflight (not per member).
        assertTrue(summaries.stream().anyMatch(
                summary -> summary.startsWith("<root>|publish-dry-run|CI workspace publish dry-run preflight")));
        assertFalse(summaries.stream().anyMatch(
                summary -> summary.startsWith("modules/core|publish-dry-run")));
        assertFalse(summaries.stream().anyMatch(
                summary -> summary.startsWith("apps/api|publish-dry-run")));
        assertTrue(summaries.contains("apps/api|target/test-reports/apps/api|CI context expected JUnit XML reports, but the report directory is missing."));
        assertTrue(summaries.contains("apps/api|target/coverage|CI context expected coverage reports, but the coverage directory is missing."));
        assertFalse(summaries.stream().anyMatch(summary -> summary.startsWith("modules/core|target/test-reports")));
        assertFalse(summaries.stream().anyMatch(summary -> summary.startsWith("modules/core|target/coverage")));
    }

    @Test
    void workspacePolicyConflictBecomesStructuredExecutionContextFailure()
            throws IOException {
        Path workspaceDir = tempDir.resolve("conflicting-workspace");
        WorkspaceMember api = member(workspaceDir, "apps/api", """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.private]
                url = "https://member.example.test/maven"
                """);
        Workspace workspace = new Workspace(
                workspaceDir,
                workspaceDir.resolve("zolt.toml"),
                new WorkspaceConfig(
                        "workspace",
                        List.of("apps/api"),
                        List.of(),
                        Map.of(
                                "private",
                                "https://root.example.test/maven"),
                        Map.of()),
                List.of(api));

        List<QualityCheckResult> results = runner.checkWorkspace(
                request(
                        workspaceDir,
                        QualityCheckContext.CI,
                        false,
                        Path.of("reports"),
                        Path.of("coverage")),
                workspace,
                new WorkspaceSelection(
                        List.of("apps/api"),
                        List.of("apps/api")),
                Map.of("apps/api", api));

        QualityCheckResult conflict = results.stream()
                .filter(result -> result.subject().equals(
                        "workspace-policy"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                QualityCheckService.EXECUTION_CONTEXT,
                conflict.id());
        assertEquals(QualityCheckStatus.FAILED, conflict.status());
        assertEquals(Optional.of("apps/api"), conflict.member());
        assertTrue(conflict.message().contains(
                "Workspace repository `private`"));
        assertTrue(conflict.nextStep().contains(
                "Make the workspace-root and member policy values match"));
    }

    private QualityCheckRequest request(
            Path projectRoot,
            QualityCheckContext context,
            boolean requirePublishDryRun,
            Path reportsDir,
            Path coverageDir) {
        return new QualityCheckRequest(
                projectRoot,
                tempDir.resolve("cache"),
                false,
                true,
                List.of(QualityCheckService.EXECUTION_CONTEXT),
                context,
                reportsDir,
                coverageDir,
                false,
                requirePublishDryRun,
                false,
                WorkspaceSelectionRequest.defaults());
    }

    private WorkspaceMember member(Path workspaceDir, String memberPath, String toml) throws IOException {
        Path memberDir = workspaceDir.resolve(memberPath);
        Files.createDirectories(memberDir);
        Files.writeString(memberDir.resolve("zolt.toml"), toml);
        ProjectConfig config = manifestLoader.load(memberDir.resolve("zolt.toml"));
        return new WorkspaceMember(memberPath, memberDir, config);
    }

    private static Workspace workspace(Path root, List<WorkspaceMember> members) {
        return new Workspace(
                root,
                root.resolve("zolt.toml"),
                new WorkspaceConfig(
                        "workspace",
                        members.stream().map(WorkspaceMember::path).toList(),
                        List.of(),
                        Map.of(),
                        Map.of()),
                members);
    }
}
