package sh.zolt.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QualityCheckServiceRoutingTest {
    @TempDir
    private Path tempDir;

    @Test
    void projectCommandSurfaceUsesTypedProjectData() throws IOException {
        Path projectDir = tempDir.resolve("single");
        writeProject(projectDir, "single");

        QualityCheckReport report = new QualityCheckService(Map.<String, String>of()::get).check(request(
                projectDir,
                false,
                List.of(QualityCheckService.COMMAND_SURFACE),
                WorkspaceSelectionRequest.defaults()));

        assertEquals("ok", report.status());
        assertFalse(report.workspace());
        QualityCheckResult result = report.checks().getFirst();
        assertEquals(QualityCheckService.COMMAND_SURFACE, result.id());
        assertEquals(QualityCheckStatus.PASSED, result.status());
        assertEquals("single", result.subject());
        assertEquals(
                "zolt check uses typed Zolt project data; no Maven, Gradle, or shell hooks are run.",
                result.message());
    }

    @Test
    void workspaceCommandSurfaceCountsSelectedMembers() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace");
        Files.createDirectories(workspaceDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "demo-workspace"
                members = ["modules/core", "apps/api"]
                """);
        writeProject(workspaceDir.resolve("modules/core"), "core");
        writeProject(workspaceDir.resolve("apps/api"), "api");

        QualityCheckReport report = new QualityCheckService(Map.<String, String>of()::get).check(request(
                workspaceDir,
                true,
                List.of(QualityCheckService.COMMAND_SURFACE),
                new WorkspaceSelectionRequest(false, List.of("apps/api"))));

        assertEquals("ok", report.status());
        assertTrue(report.workspace());
        QualityCheckResult result = report.checks().getFirst();
        assertEquals(QualityCheckService.COMMAND_SURFACE, result.id());
        assertEquals("workspace", result.subject());
        assertEquals(
                "zolt check selected 1 workspace members using typed Zolt workspace data; no Maven, Gradle, or shell hooks are run.",
                result.message());
    }

    @Test
    void workspaceRoutesEveryImplementedCheckThroughSelectedMembers() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace-all-checks");
        Files.createDirectories(workspaceDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "all-checks"
                members = ["apps/api", "modules/core"]
                """);
        writeProject(workspaceDir.resolve("apps/api"), "api");
        writeProject(workspaceDir.resolve("modules/core"), "core");
        Files.writeString(workspaceDir.resolve("zolt.lock"), "version = 7\n");

        QualityCheckReport report = new QualityCheckService(Map.<String, String>of()::get).check(request(
                workspaceDir,
                true,
                List.of(
                        QualityCheckService.CACHE_INTEGRITY,
                        QualityCheckService.EXECUTION_CONTEXT,
                        QualityCheckService.LOCKFILE,
                        QualityCheckService.PROJECT_MODEL,
                        QualityCheckService.DEPENDENCY_METADATA,
                        QualityCheckService.DEPENDENCY_POLICY,
                        QualityCheckService.PACKAGE_METADATA,
                        QualityCheckService.PACKAGE_CONTENTS,
                        QualityCheckService.MANIFEST_METADATA,
                        QualityCheckService.GENERATED_SOURCES,
                        "ant build"),
                new WorkspaceSelectionRequest(false, List.of("apps/api"))));

        assertTrue(report.workspace());
        assertEquals("error", report.status());
        assertTrue(report.checks().stream().anyMatch(result -> result.id().equals(QualityCheckService.CACHE_INTEGRITY)));
        assertTrue(report.checks().stream().anyMatch(result -> result.id().equals(QualityCheckService.EXECUTION_CONTEXT)));
        assertTrue(report.checks().stream().anyMatch(result -> result.id().equals(QualityCheckService.LOCKFILE)));
        assertTrue(report.checks().stream().anyMatch(result -> result.id().equals(QualityCheckService.PROJECT_MODEL)
                && result.member().orElse("").equals("apps/api")));
        assertTrue(report.checks().stream().anyMatch(result -> result.id().equals(QualityCheckService.DEPENDENCY_METADATA)));
        assertTrue(report.checks().stream().anyMatch(result -> result.id().equals(QualityCheckService.DEPENDENCY_POLICY)));
        assertTrue(report.checks().stream().anyMatch(result -> result.id().equals(QualityCheckService.PACKAGE_METADATA)
                && result.member().orElse("").equals("apps/api")));
        assertTrue(report.checks().stream().anyMatch(result -> result.id().equals(QualityCheckService.PACKAGE_CONTENTS)));
        assertTrue(report.checks().stream().anyMatch(result -> result.id().equals(QualityCheckService.MANIFEST_METADATA)
                && result.member().orElse("").equals("apps/api")));
        assertTrue(report.checks().stream().anyMatch(result -> result.id().equals(QualityCheckService.GENERATED_SOURCES)));
        assertTrue(report.checks().stream().anyMatch(result -> result.id().equals("unsupported-check")
                && result.subject().equals("ant build")));
    }

    @Test
    void localContextPrependsExecutionContextOnceBeforeExplicitChecks() throws IOException {
        Path projectDir = tempDir.resolve("local-with-explicit-checks");
        writeProject(projectDir, "local-with-explicit-checks");

        QualityCheckReport report = new QualityCheckService(Map.<String, String>of()::get).check(request(
                projectDir,
                false,
                List.of(QualityCheckService.EXECUTION_CONTEXT, QualityCheckService.COMMAND_SURFACE),
                QualityCheckContext.LOCAL,
                WorkspaceSelectionRequest.defaults()));

        assertEquals("ok", report.status());
        assertEquals(2, report.passedCount());
        assertEquals(0, report.failedCount());
        assertEquals(0, report.skippedCount());
        assertEquals(
                List.of(QualityCheckService.EXECUTION_CONTEXT, QualityCheckService.COMMAND_SURFACE),
                report.checks().stream().map(QualityCheckResult::id).toList());
        assertEquals("local", report.checks().getFirst().subject());
        assertEquals("local-with-explicit-checks", report.checks().get(1).subject());
    }

    @Test
    void ciContextPrependsExecutionContextToUnsupportedCheckAndAggregatesFailures() throws IOException {
        Path projectDir = tempDir.resolve("ci-unsupported");
        writeProject(projectDir, "ci-unsupported");
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        QualityCheckReport report = new QualityCheckService(Map.<String, String>of()::get).check(request(
                projectDir,
                false,
                List.of("mvn verify"),
                QualityCheckContext.CI,
                WorkspaceSelectionRequest.defaults()));

        assertEquals("error", report.status());
        assertEquals(1, report.passedCount());
        assertEquals(1, report.failedCount());
        assertEquals(0, report.skippedCount());
        assertEquals(List.of(QualityCheckService.EXECUTION_CONTEXT, "unsupported-check"), report.checks().stream()
                .map(QualityCheckResult::id)
                .toList());
        assertEquals("ci", report.checks().getFirst().subject());
        assertEquals("mvn verify", report.checks().get(1).subject());
        assertTrue(report.checks().get(1).nextStep().contains(
                "Zolt does not run Maven goals, Gradle tasks, shell commands, or arbitrary hooks."));
    }

    @Test
    void workspaceConfigErrorsReturnUnavailableResultsForRequestedChecks() throws IOException {
        Path workspaceDir = tempDir.resolve("bad-workspace");
        Files.createDirectories(workspaceDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "bad-workspace"
                members = ["apps/missing"]
                """);

        QualityCheckReport report = new QualityCheckService(Map.<String, String>of()::get).check(request(
                workspaceDir,
                true,
                List.of(QualityCheckService.LOCKFILE, "gradle build"),
                WorkspaceSelectionRequest.defaults()));

        assertEquals("error", report.status());
        assertTrue(report.workspace());
        assertEquals(2, report.checks().size());
        QualityCheckResult unavailable = report.checks().getFirst();
        assertEquals(QualityCheckService.LOCKFILE, unavailable.id());
        assertEquals("workspace config", unavailable.subject());
        assertTrue(unavailable.message().startsWith("Workspace member `apps/missing` must contain zolt.toml"));
        assertEquals("Fix workspace config or run `zolt check` for a single project.", unavailable.nextStep());
        QualityCheckResult unsupported = report.checks().get(1);
        assertEquals("unsupported-check", unsupported.id());
        assertEquals("gradle build", unsupported.subject());
        assertTrue(unsupported.nextStep().contains("Zolt does not run Maven goals, Gradle tasks, shell commands, or arbitrary hooks."));
    }

    private QualityCheckRequest request(
            Path projectRoot,
            boolean workspace,
            List<String> checks,
            WorkspaceSelectionRequest selection) {
        return request(projectRoot, workspace, checks, null, selection);
    }

    private QualityCheckRequest request(
            Path projectRoot,
            boolean workspace,
            List<String> checks,
            QualityCheckContext context,
            WorkspaceSelectionRequest selection) {
        return new QualityCheckRequest(
                projectRoot,
                tempDir.resolve("cache"),
                false,
                workspace,
                checks,
                context,
                null,
                null,
                false,
                false,
                false,
                selection);
    }

    private static void writeProject(Path projectDir, String name) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """.formatted(name));
    }
}
