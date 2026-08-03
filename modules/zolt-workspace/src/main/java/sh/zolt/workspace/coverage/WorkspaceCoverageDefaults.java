package sh.zolt.workspace.coverage;

import sh.zolt.build.coverage.CoverageReportSettings;
import sh.zolt.build.coverage.CoverageService;
import sh.zolt.build.coverage.CoverageTooling;
import sh.zolt.build.run.JavaRunResult;
import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.workspace.coverage.WorkspaceCoverageService.CoverageReporter;
import sh.zolt.workspace.coverage.WorkspaceCoverageService.CoverageWorkspaceResolver;
import sh.zolt.workspace.coverage.WorkspaceCoverageService.CoverageWorkspaceTests;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.service.WorkspaceTestResult;
import sh.zolt.workspace.service.WorkspaceTestService;
import java.nio.file.Path;
import java.util.List;

final class WorkspaceCoverageDefaults {
    private WorkspaceCoverageDefaults() {
    }

    static CoverageWorkspaceResolver resolver() {
        WorkspaceResolveService service = new WorkspaceResolveService();
        return service::resolveWithCoverageTooling;
    }

    static CoverageWorkspaceTests tests(WorkspaceTestService service) {
        return new CoverageWorkspaceTests() {
            @Override
            public WorkspaceBuildPlan planTests(
                    Path startDirectory,
                    Path cacheRoot,
                    WorkspaceSelectionRequest selectionRequest) {
                return service.planTests(
                        startDirectory,
                        cacheRoot,
                        selectionRequest);
            }

            @Override
            public WorkspaceBuildResult buildTestInputs(
                    WorkspaceBuildPlan plan,
                    Path cacheRoot) {
                return service.buildTestInputs(plan, cacheRoot);
            }

            @Override
            public WorkspaceTestResult runTests(
                    WorkspaceBuildPlan plan,
                    WorkspaceBuildResult buildResult,
                    Path cacheRoot,
                    TestSelection testSelection,
                    TestJvmArguments jvmArguments,
                    TestReportSettings reportSettings,
                    List<String> cliEvents,
                    String suiteName,
                    TestShardSpec shard) {
                return service.runTests(
                        plan,
                        buildResult,
                        cacheRoot,
                        testSelection,
                        jvmArguments,
                        reportSettings,
                        cliEvents,
                        suiteName,
                        shard);
            }
        };
    }

    static CoverageReporter reporter() {
        CoverageService service = new CoverageService();
        return new CoverageReporter() {
            @Override
            public CoverageTooling lockedCoverageTooling(
                    Path lockfileDirectory,
                    Path cacheRoot) {
                return service.lockedCoverageTooling(
                        lockfileDirectory,
                        cacheRoot);
            }

            @Override
            public TestJvmArguments coverageJvmArguments(
                    Path agentJar,
                    Path execFile,
                    boolean append) {
                return service.coverageJvmArguments(
                        agentJar,
                        execFile,
                        append);
            }

            @Override
            public void mergeWorkerExecFilesIfPresent(
                    Path projectRoot,
                    ProjectConfig config,
                    Path execFile,
                    List<Path> cliClasspath) {
                service.mergeWorkerExecFilesIfPresent(
                        projectRoot,
                        config,
                        execFile,
                        cliClasspath);
            }

            @Override
            public JavaRunResult runReport(
                    Path projectRoot,
                    ProjectConfig config,
                    CoverageReportSettings settings,
                    Path execFile,
                    List<Path> cliClasspath,
                    List<Path> classfileRoots,
                    List<Path> sourceRoots) {
                return service.runReport(
                        projectRoot,
                        config,
                        settings,
                        execFile,
                        cliClasspath,
                        classfileRoots,
                        sourceRoots);
            }
        };
    }
}
