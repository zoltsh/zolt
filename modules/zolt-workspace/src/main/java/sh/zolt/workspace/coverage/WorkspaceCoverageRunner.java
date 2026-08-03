package sh.zolt.workspace.coverage;

import sh.zolt.build.coverage.CoverageReportSettings;
import sh.zolt.build.coverage.CoverageTooling;
import sh.zolt.build.run.JavaRunResult;
import sh.zolt.project.CoverageSettings;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.coverage.WorkspaceCoverageService.CoverageReporter;
import sh.zolt.workspace.coverage.WorkspaceCoverageService.CoverageReporterFactory;
import sh.zolt.workspace.coverage.WorkspaceCoverageService.CoverageWorkspaceTests;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceJdkCheckerResolver;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceTestResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class WorkspaceCoverageRunner {
    private final CoverageWorkspaceTests workspaceTests;
    private final CoverageReporterFactory coverageReporters;
    private final WorkspaceJdkCheckerResolver reportJdkCheckers;

    WorkspaceCoverageRunner(
            CoverageWorkspaceTests workspaceTests,
            CoverageReporterFactory coverageReporters,
            WorkspaceJdkCheckerResolver reportJdkCheckers) {
        this.workspaceTests = workspaceTests;
        this.coverageReporters = coverageReporters;
        this.reportJdkCheckers = reportJdkCheckers;
    }

    WorkspaceCoverageResult run(
            WorkspaceBuildPlan plan,
            Path cacheRoot,
            TestSelection testSelection,
            CoverageReportSettings settings,
            List<String> cliEvents,
            String suiteName,
            TestShardSpec shard,
            ResolveResult resolveResult) {
        WorkspaceBuildResult buildResult =
                workspaceTests.buildTestInputs(plan, cacheRoot);
        Workspace workspace = plan.workspace();
        Path workspaceRoot = workspace.root()
                .toAbsolutePath()
                .normalize();
        List<WorkspaceMember> reportMembers =
                WorkspaceCoverageExecution.reportMembers(
                        workspace,
                        buildResult);
        WorkspaceMember reportMember =
                WorkspaceCoverageExecution.reportMember(reportMembers);
        CoverageReporter reporter = coverageReporters.create(
                workspace,
                reportMember,
                reportJdkCheckers);
        CoverageTooling tooling = reporter.lockedCoverageTooling(
                plan.lockfile(),
                cacheRoot);
        CoverageSettings coverageSettings = capturedSettings(workspace);
        Path execFile = settings.absoluteExecFile(workspaceRoot);
        WorkspaceCoverageExecution.recreateExecFile(execFile);
        TestJvmArguments jvmArguments = reporter.coverageJvmArguments(
                tooling.agentJar(),
                execFile,
                true);
        WorkspaceTestResult testResult = workspaceTests.runTests(
                plan,
                buildResult,
                cacheRoot,
                testSelection,
                jvmArguments,
                settings.testReports(),
                cliEvents,
                suiteName,
                shard);
        reporter.mergeWorkerExecFilesIfPresent(
                workspaceRoot,
                reportMember.config(),
                execFile,
                tooling.cliClasspath());
        List<Path> classfileRoots =
                WorkspaceCoverageExecution.classfileRoots(reportMembers);
        List<Path> sourceRoots =
                WorkspaceCoverageExecution.sourceRoots(reportMembers);
        JavaRunResult reportResult = reporter.runReport(
                workspaceRoot,
                reportMember.config(),
                settings,
                execFile,
                tooling.cliClasspath(),
                classfileRoots,
                sourceRoots);
        return result(
                resolveResult,
                buildResult,
                testResult,
                reportResult,
                execFile,
                settings,
                workspaceRoot,
                classfileRoots,
                sourceRoots,
                coverageSettings);
    }

    private static WorkspaceCoverageResult result(
            ResolveResult resolveResult,
            WorkspaceBuildResult buildResult,
            WorkspaceTestResult testResult,
            JavaRunResult reportResult,
            Path execFile,
            CoverageReportSettings settings,
            Path workspaceRoot,
            List<Path> classfileRoots,
            List<Path> sourceRoots,
            CoverageSettings coverageSettings) {
        return new WorkspaceCoverageResult(
                Optional.of(resolveResult),
                buildResult.members(),
                testResult.members().stream()
                        .map(member ->
                                new WorkspaceCoverageResult.MemberCoverageRunResult(
                                        member.member(),
                                        member.result()))
                        .toList(),
                reportResult.output(),
                execFile,
                settings.absoluteXmlReport(workspaceRoot),
                settings.absoluteHtmlDirectory(workspaceRoot),
                classfileRoots.size(),
                sourceRoots.size(),
                testResult.totalMemberCount(),
                testResult.toolchainMetrics(),
                coverageSettings);
    }

    private static CoverageSettings capturedSettings(Workspace workspace) {
        String content = workspace.inputs()
                .content(workspace.configPath())
                .orElseThrow(() -> new WorkspaceConfigException(
                        "Workspace coverage requires captured configuration at "
                                + workspace.configPath()
                                + "."));
        return new ZoltTomlParser().parseCoverageFloors(content);
    }
}
