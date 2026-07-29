package sh.zolt.workspace.service;

import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.build.profile.TestProfileMerger;
import sh.zolt.build.profile.TestProfileSettings;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.framework.FrameworkTestRunner;
import sh.zolt.resolve.ResolveService;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.test.TestSelection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        return new WorkspaceTestCompileExecutor(testRunServices).compile(plan, buildResult);
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
        TestJvmArguments testJvmArguments = jvmArguments == null ? TestJvmArguments.empty() : jvmArguments;
        TestReportSettings testReportSettings = reportSettings == null ? TestReportSettings.disabled() : reportSettings;
        TestProfileSettings testProfileSettings = profileSettings == null ? TestProfileSettings.disabled() : profileSettings;
        Optional<Path> workspaceProfileDirectory = testProfileSettings
                .forShard(suiteName, shard)
                .absoluteProfileDirectory(plan.workspace().root());
        Workspace workspace = plan.workspace();
        WorkspaceSelection selection = plan.selection();
        Map<String, WorkspaceMember> membersByPath = membersByPath(workspace);
        Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath = buildsByPath(buildResult);
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
            closeTestWorkers(usedServices);
        }
        workspaceProfileDirectory.ifPresent(directory -> TestProfileMerger.mergeProfiles(
                directory,
                results.stream()
                        .map(WorkspaceTestResult.MemberTestRunResult::result)
                        .map(result -> result.profileDirectory().map(path -> path.resolve("profile.json")))
                        .flatMap(Optional::stream)
                        .toList()));
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
        TestJvmArguments testJvmArguments = jvmArguments == null ? TestJvmArguments.empty() : jvmArguments;
        TestReportSettings testReportSettings = reportSettings == null ? TestReportSettings.disabled() : reportSettings;
        Workspace workspace = plan.workspace();
        WorkspaceSelection selection = plan.selection();
        Map<String, WorkspaceMember> membersByPath = membersByPath(workspace);
        Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath = buildsByPath(buildResult);
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
            closeTestWorkers(usedServices);
        }
        return new WorkspaceTestResult(
                buildResult.resolveResult(),
                buildResult.members(),
                results,
                workspace.members().size());
    }

    private static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }

    private static void closeTestWorkers(
            List<TestRunService> services) {
        RuntimeException firstFailure = null;
        for (TestRunService service : services.stream().distinct().toList()) {
            try {
                service.closeTestWorkers();
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private static Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath(WorkspaceBuildResult result) {
        Map<String, WorkspaceBuildResult.MemberBuildResult> builds = new LinkedHashMap<>();
        for (WorkspaceBuildResult.MemberBuildResult member : result.members()) {
            builds.put(member.member(), member);
        }
        return builds;
    }

}
