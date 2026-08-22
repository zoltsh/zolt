package sh.zolt.cli.command.testcmd;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandBuildProvenance;
import sh.zolt.cli.command.CommandLockfiles;
import sh.zolt.cli.command.CommandServiceBundles.TestRunServiceFactory;
import sh.zolt.cli.command.CommandToolchainOptions;
import sh.zolt.cli.command.build.CommandBuildAttributes;
import sh.zolt.cli.console.ProgressWriter;
import sh.zolt.perf.TimingRecorder;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.test.WorkspaceTestResult;
import sh.zolt.workspace.test.WorkspaceTestService;
import java.nio.file.Path;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Runs tests through the workspace: gate the root lock, plan the selection, build its inputs in
 * dependency order, then run the selected members' tests.
 *
 * <p>Reached both by {@code zolt test --workspace} and by a member-directory {@code zolt test},
 * which design §4.5 makes the same operation with a narrower selection.
 */
final class WorkspaceTestCommandRunner {
    private final WorkspaceTestService workspaceTestService;
    private final TestRunServiceFactory testRunServiceFactory;
    private final CommandLockfiles lockfiles;
    private final CommandToolchainOptions toolchainOptions;
    private final CommandSpec spec;

    WorkspaceTestCommandRunner(
            WorkspaceTestService workspaceTestService,
            TestRunServiceFactory testRunServiceFactory,
            CommandLockfiles lockfiles,
            CommandToolchainOptions toolchainOptions,
            CommandSpec spec) {
        this.workspaceTestService = workspaceTestService;
        this.testRunServiceFactory = testRunServiceFactory;
        this.lockfiles = lockfiles;
        this.toolchainOptions = toolchainOptions;
        this.spec = spec;
    }

    void runTests(
            Path workspaceRoot,
            Path cacheRoot,
            WorkspaceSelectionRequest selection,
            TimingRecorder timings,
            ProgressWriter progress,
            TestCommandRequest request) {
        var workspaceToolchains = toolchainOptions.workspaceTestToolchains(testRunServiceFactory, "test");
        WorkspaceTestService projectWorkspaceTestService = workspaceTestService.withMemberServices(
                workspaceToolchains.mainCheckers(),
                workspaceToolchains.testRunServices());
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        WorkspaceTestResult result = WorkspaceMutationLock.withWorkspaceLock(
                workspaceRoot,
                () -> {
                    var target = lockfiles.requireFreshWorkspaceLockfile(
                            timings, workspaceRoot, cacheRoot, false);
                    progress.start("Testing workspace");
                    return timings.measure(
                            "test workspace",
                            () -> {
                                WorkspaceBuildPlan plan = timings.measure(
                                        "plan workspace tests",
                                        () -> projectWorkspaceTestService.planTests(
                                                target,
                                                cacheRoot,
                                                selection),
                                        CommandBuildAttributes::workspaceBuildPlan);
                                WorkspaceBuildResult buildResult = timings.measure(
                                        "build workspace test inputs",
                                        () -> projectWorkspaceTestService.buildTestInputs(plan, cacheRoot),
                                        build -> CommandBuildAttributes.workspaceBuild(build, plan.selection()));
                                return timings.measure(
                                        "run workspace test members",
                                        () -> projectWorkspaceTestService.runTests(
                                                plan,
                                                buildResult,
                                                cacheRoot,
                                                request.testSelection(),
                                                request.testJvmArguments(),
                                                request.reportSettings(),
                                                request.requestedTestEvents(),
                                                request.suiteName(),
                                                request.shard(),
                                                request.profileSettings(),
                                                request.concurrency()),
                                        CommandTestAttributes::workspaceTest);
                            },
                            CommandTestAttributes::workspaceTest);
                });
        if (result.resolvedLockfile()) {
            output.detail("Resolved workspace dependencies because zolt.lock was missing");
        }
        WorkspaceTestCommandOutput.printMembers(spec, output, result);
        result.profileDirectory().ifPresent(directory ->
                CommandTestProfileOutput.print(output, directory, request.profileSettings()));
        int testedMembers = result.members().size();
        String summary = testedMembers < result.totalMemberCount()
                ? "Tested " + testedMembers + " of " + result.totalMemberCount()
                        + " workspace members; use --all to test every member"
                : "Tests passed for " + testedMembers + " workspace members";
        output.summary(summary, testedMembers + " members");
        output.provenance(CommandBuildProvenance.read(workspaceRoot));
        progress.result("Tested " + testedMembers + " workspace members");
    }
}
