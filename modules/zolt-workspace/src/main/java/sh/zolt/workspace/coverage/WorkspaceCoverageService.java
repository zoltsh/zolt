package sh.zolt.workspace.coverage;

import sh.zolt.build.coverage.CoverageReportSettings;
import sh.zolt.build.coverage.CoverageService;
import sh.zolt.build.coverage.CoverageTooling;
import sh.zolt.build.run.JavaRunResult;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceJdkCheckerResolver;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.service.WorkspaceTestResult;
import sh.zolt.workspace.service.WorkspaceTestRunServiceResolver;
import sh.zolt.workspace.service.WorkspaceTestService;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

public final class WorkspaceCoverageService {
    private final CoverageWorkspaceDiscovery workspaceDiscovery;
    private final CoverageWorkspaceResolver workspaceResolver;
    private final CoverageWorkspaceTests workspaceTests;
    private final CoverageReporterFactory coverageReporters;
    private final WorkspaceTestService configurableWorkspaceTests;
    private final WorkspaceJdkCheckerResolver reportJdkCheckers;

    public WorkspaceCoverageService() {
        this(new WorkspaceTestService());
    }

    public WorkspaceCoverageService(
            WorkspaceTestService workspaceTestService) {
        this(
                new WorkspaceResolveService(),
                workspaceTestService,
                checker -> new CoverageService(
                        checker,
                        new sh.zolt.resolve.ResolveService()));
    }

    public WorkspaceCoverageService(
            WorkspaceResolveService workspaceResolveService,
            WorkspaceTestService workspaceTestService,
            Function<JdkChecker, CoverageService> coverageServices) {
        this(
                WorkspaceCoverageDefaults.discovery(),
                WorkspaceCoverageDefaults.resolver(
                        workspaceResolveService),
                WorkspaceCoverageDefaults.tests(workspaceTestService),
                WorkspaceCoverageDefaults.reporterFactory(
                        coverageServices),
                workspaceTestService,
                WorkspaceJdkCheckerResolver.fixed(new JdkDetector()));
    }

    WorkspaceCoverageService(
            CoverageWorkspaceDiscovery workspaceDiscovery,
            CoverageWorkspaceResolver workspaceResolver,
            CoverageWorkspaceTests workspaceTests,
            CoverageReporter coverageReporter) {
        this(
                workspaceDiscovery,
                workspaceResolver,
                workspaceTests,
                (workspace, reportMember, jdkCheckers) ->
                        coverageReporter,
                null,
                WorkspaceJdkCheckerResolver.fixed(new JdkDetector()));
    }

    WorkspaceCoverageService(
            CoverageWorkspaceDiscovery workspaceDiscovery,
            CoverageWorkspaceResolver workspaceResolver,
            CoverageWorkspaceTests workspaceTests,
            CoverageReporterFactory coverageReporters,
            WorkspaceTestService configurableWorkspaceTests,
            WorkspaceJdkCheckerResolver reportJdkCheckers) {
        this.workspaceDiscovery = workspaceDiscovery;
        this.workspaceResolver = workspaceResolver;
        this.workspaceTests = workspaceTests;
        this.coverageReporters = coverageReporters;
        this.configurableWorkspaceTests = configurableWorkspaceTests;
        this.reportJdkCheckers = reportJdkCheckers;
    }

    public WorkspaceCoverageService withMemberServices(
            WorkspaceJdkCheckerResolver jdkCheckers,
            WorkspaceTestRunServiceResolver testRunServices) {
        if (configurableWorkspaceTests == null) {
            throw new IllegalStateException(
                    "This workspace coverage service does not own a configurable WorkspaceTestService.");
        }
        WorkspaceTestService configured =
                configurableWorkspaceTests.withMemberServices(
                        jdkCheckers,
                        testRunServices);
        return new WorkspaceCoverageService(
                workspaceDiscovery,
                workspaceResolver,
                WorkspaceCoverageDefaults.tests(configured),
                coverageReporters,
                configured,
                jdkCheckers);
    }

    public WorkspaceCoverageResult runCoverage(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest,
            TestSelection testSelection,
            CoverageReportSettings reportSettings,
            List<String> cliEvents) {
        return runCoverage(
                startDirectory,
                cacheRoot,
                selectionRequest,
                testSelection,
                reportSettings,
                cliEvents,
                "all",
                null);
    }

    public WorkspaceCoverageResult runCoverage(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest,
            TestSelection testSelection,
            CoverageReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName,
            TestShardSpec shard) {
        CoverageReportSettings settings = reportSettings == null
                ? CoverageReportSettings.defaults()
                : reportSettings;
        CoverageReportSettings finalSettings =
                settings.forShard(suiteName, shard);
        return WorkspaceMutationLock.withWorkspaceLock(
                startDirectory,
                () -> runCoverageLocked(
                        startDirectory,
                        cacheRoot,
                        selectionRequest,
                        testSelection,
                        finalSettings,
                        cliEvents,
                        suiteName,
                        shard));
    }

    private WorkspaceCoverageResult runCoverageLocked(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest,
            TestSelection testSelection,
            CoverageReportSettings settings,
            List<String> cliEvents,
            String suiteName,
            TestShardSpec shard) {
        Workspace resolveWorkspace =
                workspaceDiscovery.discover(startDirectory);
        ResolveResult resolveResult =
                workspaceResolver.resolveWithCoverageTooling(
                        resolveWorkspace,
                        cacheRoot);
        WorkspaceBuildPlan plan = workspaceTests.planTests(
                startDirectory,
                cacheRoot,
                selectionRequest);
        resolveWorkspace.inputs().requireCurrent();
        plan.requireInputsCurrent();
        return new WorkspaceCoverageRunner(
                workspaceTests,
                coverageReporters,
                reportJdkCheckers).run(
                plan,
                cacheRoot,
                testSelection,
                settings,
                cliEvents,
                suiteName,
                shard,
                resolveResult);
    }

    @FunctionalInterface
    interface CoverageWorkspaceDiscovery {
        Workspace discover(Path startDirectory);
    }

    @FunctionalInterface
    interface CoverageWorkspaceResolver {
        ResolveResult resolveWithCoverageTooling(
                Workspace workspace,
                Path cacheRoot);
    }

    interface CoverageWorkspaceTests {
        WorkspaceBuildPlan planTests(
                Path startDirectory,
                Path cacheRoot,
                WorkspaceSelectionRequest selectionRequest);

        WorkspaceBuildResult buildTestInputs(
                WorkspaceBuildPlan plan,
                Path cacheRoot);

        WorkspaceTestResult runTests(
                WorkspaceBuildPlan plan,
                WorkspaceBuildResult buildResult,
                Path cacheRoot,
                TestSelection testSelection,
                TestJvmArguments jvmArguments,
                sh.zolt.build.testruntime.TestReportSettings reportSettings,
                List<String> cliEvents,
                String suiteName,
                TestShardSpec shard);
    }

    @FunctionalInterface
    interface CoverageReporterFactory {
        CoverageReporter create(
                Workspace workspace,
                WorkspaceMember reportMember,
                WorkspaceJdkCheckerResolver jdkCheckers);
    }

    interface CoverageReporter {
        CoverageTooling lockedCoverageTooling(
                ZoltLockfile lockfile,
                Path cacheRoot);

        TestJvmArguments coverageJvmArguments(
                Path agentJar,
                Path execFile,
                boolean append);

        default void mergeWorkerExecFilesIfPresent(
                Path projectRoot,
                ProjectConfig config,
                Path execFile,
                List<Path> cliClasspath) {
        }

        JavaRunResult runReport(
                Path projectRoot,
                ProjectConfig config,
                CoverageReportSettings settings,
                Path execFile,
                List<Path> cliClasspath,
                List<Path> classfileRoots,
                List<Path> sourceRoots);
    }
}
