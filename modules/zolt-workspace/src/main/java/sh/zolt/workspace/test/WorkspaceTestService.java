package sh.zolt.workspace.test;

import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.profile.TestProfileSettings;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.framework.FrameworkTestRunner;
import sh.zolt.resolve.ResolveService;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.test.TestSelection;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildRequirements;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceBuildService;
import sh.zolt.workspace.service.WorkspaceJdkCheckerResolver;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.service.WorkspaceTestStateCommitter;
import sh.zolt.workspace.testpool.WorkspaceTestConcurrency;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class WorkspaceTestService {
    private final WorkspaceBuildService workspaceBuildService;
    private final WorkspaceTestRunServiceResolver testRunServices;
    private final Optional<BuildCacheService> buildCacheService;
    private final WorkspaceTestStateCommitter testStateCommitter = new WorkspaceTestStateCommitter();

    public WorkspaceTestService() {
        this(new JdkDetector());
    }

    public WorkspaceTestService(ResolveService resolveService, FrameworkTestRunner frameworkTestRunner) {
        this(new JdkDetector(), resolveService, frameworkTestRunner);
    }

    public WorkspaceTestService(JdkChecker jdkDetector) {
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
        this(
                workspaceBuildService,
                WorkspaceTestRunServiceResolver.fixed(testRunService),
                Optional.empty());
    }

    private WorkspaceTestService(
            WorkspaceBuildService workspaceBuildService,
            WorkspaceTestRunServiceResolver testRunServices,
            Optional<BuildCacheService> buildCacheService) {
        this.workspaceBuildService = workspaceBuildService;
        this.testRunServices = testRunServices;
        this.buildCacheService = buildCacheService;
    }

    public WorkspaceTestService withMemberServices(
            WorkspaceJdkCheckerResolver jdkCheckers,
            WorkspaceTestRunServiceResolver testRunServices) {
        return new WorkspaceTestService(
                workspaceBuildService.withJdkCheckers(jdkCheckers),
                testRunServices,
                buildCacheService);
    }

    /** Uses one shared output cache for workspace main and selected test compilations. */
    public WorkspaceTestService withBuildCache(BuildCacheService buildCacheService) {
        Objects.requireNonNull(buildCacheService, "buildCacheService");
        return new WorkspaceTestService(
                workspaceBuildService.withBuildCache(buildCacheService),
                testRunServices,
                Optional.of(buildCacheService));
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
        return WorkspaceMutationLock.withWorkspaceLock(startDirectory, () -> {
            WorkspaceBuildPlan plan = planTests(WorkspacePlanTarget.at(startDirectory), cacheRoot, selectionRequest);
            return runTests(plan, buildTestInputs(plan, cacheRoot), cacheRoot,
                    testSelection, jvmArguments);
        });
    }

    public WorkspaceBuildPlan planTests(
            WorkspacePlanTarget target,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest) {
        return workspaceBuildService.planTestBuild(target, cacheRoot, false, selectionRequest);
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
            WorkspaceTestCompileResult result = new WorkspaceTestCompileExecutor(cacheAwareTestRunServices())
                    .compile(plan.requireInputsCurrent(), buildResult);
            commitTestState(plan, buildResult);
            return result;
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
                profileSettings,
                WorkspaceTestConcurrency.adaptive());
    }

    /**
     * Run every selected member's tests.
     *
     * @param concurrency how many members may run at once; adaptive scales with the machine
     */
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
            TestProfileSettings profileSettings,
            WorkspaceTestConcurrency concurrency) {
        try (WorkspaceMutationLock ignored =
                WorkspaceMutationLock.acquire(plan.workspace().root())) {
            WorkspaceTestResult result = new WorkspaceTestRunner(cacheAwareTestRunServices(), concurrency)
                    .runUnit(
                            plan,
                            buildResult,
                            testSelection,
                            jvmArguments,
                            reportSettings,
                            cliEvents,
                            suiteName,
                            shard,
                            profileSettings);
            commitTestState(plan, buildResult);
            return result;
        }
    }

    public WorkspaceTestResult runIntegrationTests(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Path cacheRoot,
            TestSelection testSelection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents) {
        return runIntegrationTests(
                plan,
                buildResult,
                cacheRoot,
                testSelection,
                jvmArguments,
                reportSettings,
                cliEvents,
                WorkspaceTestConcurrency.adaptive());
    }

    public WorkspaceTestResult runIntegrationTests(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Path cacheRoot,
            TestSelection testSelection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            WorkspaceTestConcurrency concurrency) {
        try (WorkspaceMutationLock ignored =
                WorkspaceMutationLock.acquire(plan.workspace().root())) {
            return new WorkspaceTestRunner(cacheAwareTestRunServices(), concurrency)
                    .runIntegration(
                            plan,
                            buildResult,
                            testSelection,
                            jvmArguments,
                            reportSettings,
                            cliEvents);
        }
    }

    private void commitTestState(WorkspaceBuildPlan plan, WorkspaceBuildResult buildResult) {
        testStateCommitter.commitSelected(
                plan,
                buildResult.membersRequiringTestCompile());
    }

    private WorkspaceTestRunServiceResolver cacheAwareTestRunServices() {
        return buildCacheService.map(testRunServices::withBuildCache).orElse(testRunServices);
    }
}
