package sh.zolt.workspace.service;

import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.build.profile.TestProfileSettings;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.framework.FrameworkTestRunner;
import sh.zolt.resolve.ResolveService;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.test.TestSelection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class WorkspaceTestService {
    private final WorkspaceBuildService workspaceBuildService;
    private final WorkspaceTestRunServiceResolver testRunServices;

    public WorkspaceTestService() {
        this(new JdkDetector());
    }

    public WorkspaceTestService(ResolveService resolveService, FrameworkTestRunner frameworkTestRunner) {
        this(new JdkDetector(), resolveService, frameworkTestRunner);
    }

    WorkspaceTestService(JdkChecker jdkDetector) {
        this(jdkDetector, new ResolveService(), FrameworkTestRunner.none());
    }

    WorkspaceTestService(
            JdkChecker jdkDetector,
            ResolveService resolveService,
            FrameworkTestRunner frameworkTestRunner) {
        this(
                new WorkspaceBuildService(jdkDetector, resolveService),
                new TestRunService(jdkDetector, frameworkTestRunner, resolveService));
    }

    WorkspaceTestService(
            WorkspaceBuildService workspaceBuildService,
            TestRunService testRunService) {
        this.workspaceBuildService = workspaceBuildService;
        this.testRunServices = WorkspaceTestRunServiceResolver.fixed(testRunService);
    }

    private WorkspaceTestService(
            WorkspaceBuildService workspaceBuildService,
            WorkspaceTestRunServiceResolver testRunServices) {
        this.workspaceBuildService = workspaceBuildService;
        this.testRunServices = testRunServices;
    }

    public WorkspaceTestService withMemberServices(
            WorkspaceJdkCheckerResolver jdkCheckers,
            WorkspaceTestRunServiceResolver testRunServices) {
        return new WorkspaceTestService(
                workspaceBuildService.withJdkCheckers(jdkCheckers),
                testRunServices);
    }

    public WorkspaceTestResult test(Path startDirectory, Path cacheRoot) {
        return test(startDirectory, cacheRoot, WorkspaceSelectionRequest.defaults());
    }

    public WorkspaceTestResult test(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest) {
        return test(startDirectory, cacheRoot, selectionRequest, TestSelection.empty());
    }

    public WorkspaceTestResult test(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest,
            TestSelection testSelection) {
        return test(startDirectory, cacheRoot, selectionRequest, testSelection, TestJvmArguments.empty());
    }

    public WorkspaceTestResult test(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest,
            TestSelection testSelection,
            TestJvmArguments jvmArguments) {
        WorkspaceBuildPlan plan = planTests(startDirectory, cacheRoot, selectionRequest);
        WorkspaceBuildResult buildResult = buildTestInputs(plan, cacheRoot);
        return runTests(plan, buildResult, cacheRoot, testSelection, jvmArguments);
    }

    public WorkspaceBuildPlan planTests(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest) {
        return workspaceBuildService.planTestBuild(startDirectory, cacheRoot, false, selectionRequest);
    }

    public WorkspaceBuildResult buildTestInputs(WorkspaceBuildPlan plan, Path cacheRoot) {
        return workspaceBuildService.build(
                plan,
                cacheRoot,
                WorkspaceBuildRequirements.testRun());
    }

    public WorkspaceBuildResult buildTestCompileInputs(WorkspaceBuildPlan plan, Path cacheRoot) {
        return workspaceBuildService.build(
                plan,
                cacheRoot,
                WorkspaceBuildRequirements.testCompile());
    }

    public WorkspaceTestCompileResult compileTests(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult) {
        try (WorkspaceMutationLock ignored =
                WorkspaceMutationLock.acquire(plan.workspace().root())) {
            return new WorkspaceTestCompileExecutor(testRunServices)
                    .compile(plan, buildResult);
        }
    }

    public WorkspaceTestResult runTests(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Path cacheRoot) {
        return runTests(plan, buildResult, cacheRoot, TestSelection.empty());
    }

    public WorkspaceTestResult runTests(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Path cacheRoot,
            TestSelection testSelection) {
        return runTests(plan, buildResult, cacheRoot, testSelection, TestJvmArguments.empty());
    }

    public WorkspaceTestResult runTests(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Path cacheRoot,
            TestSelection testSelection,
            TestJvmArguments jvmArguments) {
        return runTests(plan, buildResult, cacheRoot, testSelection, jvmArguments, TestReportSettings.disabled());
    }

    public WorkspaceTestResult runTests(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Path cacheRoot,
            TestSelection testSelection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings) {
        return runTests(plan, buildResult, cacheRoot, testSelection, jvmArguments, reportSettings, List.of());
    }

    public WorkspaceTestResult runTests(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Path cacheRoot,
            TestSelection testSelection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents) {
        return runTests(plan, buildResult, cacheRoot, testSelection, jvmArguments, reportSettings, cliEvents, "all");
    }

    public WorkspaceTestResult runTests(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Path cacheRoot,
            TestSelection testSelection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName) {
        return runTests(plan, buildResult, cacheRoot, testSelection, jvmArguments, reportSettings, cliEvents, suiteName, null);
    }

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
        return runTests(
                plan,
                buildResult,
                cacheRoot,
                testSelection,
                jvmArguments,
                reportSettings,
                cliEvents,
                suiteName,
                shard,
                TestProfileSettings.disabled());
    }

    public WorkspaceTestResult runTests(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Path cacheRoot,
            TestSelection testSelection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName,
            TestShardSpec shard,
            TestProfileSettings profileSettings) {
        try (WorkspaceMutationLock ignored =
                WorkspaceMutationLock.acquire(plan.workspace().root())) {
            return runTestsLocked(
                    plan,
                    buildResult,
                    cacheRoot,
                    testSelection,
                    jvmArguments,
                    reportSettings,
                    cliEvents,
                    suiteName,
                    shard,
                    profileSettings);
        }
    }

    private WorkspaceTestResult runTestsLocked(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Path cacheRoot,
            TestSelection testSelection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName,
            TestShardSpec shard,
            TestProfileSettings profileSettings) {
        TestJvmArguments testJvmArguments = jvmArguments == null ? TestJvmArguments.empty() : jvmArguments;
        TestReportSettings testReportSettings = reportSettings == null ? TestReportSettings.disabled() : reportSettings;
        TestProfileSettings testProfileSettings = profileSettings == null ? TestProfileSettings.disabled() : profileSettings;
        Optional<Path> workspaceProfileDirectory = testProfileSettings
                .forShard(suiteName, shard)
                .absoluteProfileDirectory(plan.workspace().root());
        Workspace workspace = plan.workspace();
        WorkspaceSelection selection = plan.selection();
        Map<String, WorkspaceMember> membersByPath = WorkspaceTestExecutionSupport.membersByPath(workspace);
        Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath =
                WorkspaceTestExecutionSupport.buildsByPath(buildResult);
        List<TestRunService> usedServices = new ArrayList<>();
        var tasks = WorkspaceTestTasks.unit(
                workspace,
                selection.selectedMembers(),
                membersByPath,
                buildsByPath,
                testRunServices,
                usedServices,
                testSelection,
                testJvmArguments,
                testReportSettings,
                cliEvents,
                suiteName,
                shard,
                testProfileSettings);
        List<WorkspaceTestResult.MemberTestRunResult> results;
        try {
            results = new WorkspaceTestExecutor().execute(tasks);
        } finally {
            WorkspaceTestExecutionSupport.closeTestWorkers(usedServices);
        }
        WorkspaceTestExecutionSupport.mergeProfiles(
                workspaceProfileDirectory,
                results);
        return new WorkspaceTestResult(
                buildResult.resolveResult(),
                buildResult.members(),
                results,
                workspace.members().size(),
                workspaceProfileDirectory);
    }

    public WorkspaceTestResult runIntegrationTests(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Path cacheRoot,
            TestSelection testSelection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents) {
        try (WorkspaceMutationLock ignored =
                WorkspaceMutationLock.acquire(plan.workspace().root())) {
            return runIntegrationTestsLocked(
                    plan,
                    buildResult,
                    cacheRoot,
                    testSelection,
                    jvmArguments,
                    reportSettings,
                    cliEvents);
        }
    }

    private WorkspaceTestResult runIntegrationTestsLocked(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Path cacheRoot,
            TestSelection testSelection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents) {
        TestJvmArguments testJvmArguments = jvmArguments == null ? TestJvmArguments.empty() : jvmArguments;
        TestReportSettings testReportSettings = reportSettings == null ? TestReportSettings.disabled() : reportSettings;
        Workspace workspace = plan.workspace();
        WorkspaceSelection selection = plan.selection();
        Map<String, WorkspaceMember> membersByPath = WorkspaceTestExecutionSupport.membersByPath(workspace);
        Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath =
                WorkspaceTestExecutionSupport.buildsByPath(buildResult);
        List<TestRunService> usedServices = new ArrayList<>();
        var tasks = WorkspaceTestTasks.integration(
                workspace,
                selection.selectedMembers(),
                membersByPath,
                buildsByPath,
                testRunServices,
                usedServices,
                testSelection,
                testJvmArguments,
                testReportSettings,
                cliEvents);
        List<WorkspaceTestResult.MemberTestRunResult> results;
        try {
            results = new WorkspaceTestExecutor().execute(tasks);
        } finally {
            WorkspaceTestExecutionSupport.closeTestWorkers(usedServices);
        }
        return new WorkspaceTestResult(
                buildResult.resolveResult(),
                buildResult.members(),
                results,
                workspace.members().size());
    }

}
