package sh.zolt.workspace.test;

import sh.zolt.build.profile.TestProfileSettings;
import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.build.testruntime.TestRunResult;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.workspace.testpool.WorkspaceTestConcurrency;
import sh.zolt.workspace.testpool.WorkspaceTestPoolMetrics;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.testpool.WorkspaceTestSchedule;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Fans the selected members out across test JVMs and assembles the workspace result.
 *
 * <p>Members are submitted heaviest-first but their results land back in selection order, so the
 * pool width never changes what the caller prints.
 */
final class WorkspaceTestRunner {
    private final WorkspaceTestRunServiceResolver testRunServices;
    private final WorkspaceTestConcurrency concurrency;

    WorkspaceTestRunner(
            WorkspaceTestRunServiceResolver testRunServices,
            WorkspaceTestConcurrency concurrency) {
        this.testRunServices = testRunServices;
        this.concurrency = concurrency == null
                ? WorkspaceTestConcurrency.adaptive()
                : concurrency;
    }

    WorkspaceTestResult runUnit(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            TestSelection testSelection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName,
            TestShardSpec shard,
            TestProfileSettings profileSettings) {
        TestProfileSettings testProfileSettings =
                profileSettings == null ? TestProfileSettings.disabled() : profileSettings;
        Optional<Path> workspaceProfileDirectory = testProfileSettings
                .forShard(suiteName, shard)
                .absoluteProfileDirectory(plan.workspace().root());
        Workspace workspace = plan.requireInputsCurrent().workspace();
        List<String> memberPaths = plan.selection().selectedMembers();
        Map<String, WorkspaceMember> membersByPath =
                WorkspaceTestExecutionSupport.membersByPath(workspace);
        List<TestRunService> usedServices = new ArrayList<>();
        var tasks = WorkspaceTestTasks.unit(
                workspace,
                memberPaths,
                membersByPath,
                WorkspaceTestExecutionSupport.buildsByPath(buildResult),
                testRunServices,
                usedServices,
                testSelection,
                arguments(jvmArguments),
                reports(reportSettings),
                cliEvents,
                suiteName,
                shard,
                testProfileSettings);
        var execution = execute(tasks, memberPaths, membersByPath, usedServices);
        WorkspaceTestExecutionSupport.mergeProfiles(
                workspaceProfileDirectory,
                execution.results());
        return result(buildResult, execution, workspace, workspaceProfileDirectory);
    }

    WorkspaceTestResult runIntegration(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            TestSelection testSelection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents) {
        Workspace workspace = plan.requireInputsCurrent().workspace();
        List<String> memberPaths = plan.selection().selectedMembers();
        Map<String, WorkspaceMember> membersByPath =
                WorkspaceTestExecutionSupport.membersByPath(workspace);
        List<TestRunService> usedServices = new ArrayList<>();
        var tasks = WorkspaceTestTasks.integration(
                workspace,
                memberPaths,
                membersByPath,
                WorkspaceTestExecutionSupport.buildsByPath(buildResult),
                testRunServices,
                usedServices,
                testSelection,
                arguments(jvmArguments),
                reports(reportSettings),
                cliEvents);
        var execution = execute(tasks, memberPaths, membersByPath, usedServices);
        return result(buildResult, execution, workspace, Optional.empty());
    }

    private <T> WorkspaceTestExecutor.Execution<T> execute(
            List<Callable<T>> tasks,
            List<String> memberPaths,
            Map<String, WorkspaceMember> membersByPath,
            List<TestRunService> usedServices) {
        List<Integer> submissionOrder = WorkspaceTestSchedule.order(
                memberPaths,
                WorkspaceTestSchedule.testSourceWeights(memberPaths, membersByPath));
        try {
            return new WorkspaceTestExecutor(concurrency).run(tasks, submissionOrder);
        } finally {
            WorkspaceTestExecutionSupport.closeTestWorkers(usedServices);
        }
    }

    private WorkspaceTestResult result(
            WorkspaceBuildResult buildResult,
            WorkspaceTestExecutor.Execution<WorkspaceTestResult.MemberTestRunResult> execution,
            Workspace workspace,
            Optional<Path> profileDirectory) {
        return new WorkspaceTestResult(
                buildResult.resolveResult(),
                buildResult.members(),
                execution.results(),
                workspace.members().size(),
                profileDirectory,
                WorkspaceTestToolchainMetrics.combine(
                        buildResult.executionMetrics(),
                        testRunServices.toolchainMetrics()),
                poolMetrics(execution));
    }

    /** Worker timings the members already collected, rolled up for the whole run. */
    private static WorkspaceTestPoolMetrics poolMetrics(
            WorkspaceTestExecutor.Execution<WorkspaceTestResult.MemberTestRunResult> execution) {
        return new WorkspaceTestPoolMetrics(
                execution.workers(),
                execution.queueNanos(),
                sum(execution, TestRunResult::testRunnerStartupNanos),
                sum(execution, TestRunResult::testRunnerRequestNanos));
    }

    /** Unknown timings are reported as negative by the runner; they contribute nothing. */
    private static long sum(
            WorkspaceTestExecutor.Execution<WorkspaceTestResult.MemberTestRunResult> execution,
            java.util.function.ToLongFunction<TestRunResult> timing) {
        return execution.results().stream()
                .map(WorkspaceTestResult.MemberTestRunResult::result)
                .mapToLong(timing)
                .filter(nanos -> nanos >= 0L)
                .sum();
    }

    private static TestJvmArguments arguments(TestJvmArguments jvmArguments) {
        return jvmArguments == null ? TestJvmArguments.empty() : jvmArguments;
    }

    private static TestReportSettings reports(TestReportSettings reportSettings) {
        return reportSettings == null ? TestReportSettings.disabled() : reportSettings;
    }
}
