package sh.zolt.workspace.coverage;

import sh.zolt.build.coverage.CoverageReportSettings;
import sh.zolt.build.coverage.CoverageService;
import sh.zolt.build.coverage.CoverageTooling;
import sh.zolt.build.run.JavaRunResult;
import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.workspace.coverage.WorkspaceCoverageService.CoverageReporter;
import sh.zolt.workspace.coverage.WorkspaceCoverageService.CoverageReporterFactory;
import sh.zolt.workspace.coverage.WorkspaceCoverageService.CoverageWorkspaceDiscovery;
import sh.zolt.workspace.coverage.WorkspaceCoverageService.CoverageWorkspaceResolver;
import sh.zolt.workspace.coverage.WorkspaceCoverageService.CoverageWorkspaceTests;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceJdkCheckerResolver;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.test.WorkspaceTestResult;
import sh.zolt.workspace.test.WorkspaceTestService;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

final class WorkspaceCoverageDefaults {
    private WorkspaceCoverageDefaults() {
    }

    static CoverageWorkspaceDiscovery discovery() {
        WorkspaceDiscoveryService service = new WorkspaceDiscoveryService();
        return start -> service.discover(start).orElseThrow(() ->
                new sh.zolt.workspace.WorkspaceConfigException(
                        "Could not find workspace config for coverage."));
    }

    static CoverageWorkspaceResolver resolver(
            WorkspaceResolveService service) {
        return service::resolveCoverageSnapshot;
    }

    static CoverageWorkspaceTests tests(WorkspaceTestService service) {
        return new CoverageWorkspaceTests() {
            @Override
            public WorkspaceBuildPlan planTests(
                    Path startDirectory,
                    Path cacheRoot,
                    WorkspaceSelectionRequest selectionRequest) {
                return service.planTests(
                        WorkspacePlanTarget.at(startDirectory),
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

    static CoverageReporterFactory reporterFactory(
            Function<JdkChecker, CoverageService> services) {
        Objects.requireNonNull(services, "services");
        return (
                Workspace workspace,
                WorkspaceMember reportMember,
                WorkspaceJdkCheckerResolver jdkCheckers) -> reporter(
                        services.apply(jdkCheckers.forMember(
                                workspace,
                                reportMember)));
    }

    static CoverageReporter reporter(CoverageService service) {
        return new CoverageReporter() {
            @Override
            public CoverageTooling lockedCoverageTooling(
                    ZoltLockfile lockfile,
                    Path cacheRoot) {
                return service.lockedCoverageTooling(
                        lockfile,
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
