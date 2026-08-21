package sh.zolt.cli.command.testcmd;

import sh.zolt.build.BuildResultWithClasspaths;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandBuildCache;
import sh.zolt.cli.command.CommandBuildProvenance;
import sh.zolt.cli.command.CommandLockfiles;
import sh.zolt.cli.command.CommandServiceBundles.TestRunServiceFactory;
import sh.zolt.cli.command.CommandToolchainOptions;
import sh.zolt.cli.command.CommandWorkspaceSelections;
import sh.zolt.cli.command.build.CommandBuildAttributes;
import sh.zolt.cli.console.ProgressWriter;
import sh.zolt.perf.TimingRecorder;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import sh.zolt.workspace.test.WorkspaceTestCompileResult;
import sh.zolt.workspace.test.WorkspaceTestService;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Model.CommandSpec;

final class TestCompileCommandRunner {
    private final ManifestProjectLoader projectLoader;
    private final WorkspaceTestService workspaceTestService;
    private final TestRunServiceFactory testRunServiceFactory;
    private final CommandLockfiles lockfiles;
    private final CommandToolchainOptions toolchainOptions;
    private final CommandSpec spec;

    TestCompileCommandRunner(
            ManifestProjectLoader projectLoader,
            WorkspaceTestService workspaceTestService,
            TestRunServiceFactory testRunServiceFactory,
            CommandLockfiles lockfiles,
            CommandToolchainOptions toolchainOptions,
            CommandSpec spec) {
        this.projectLoader = projectLoader;
        this.workspaceTestService = workspaceTestService;
        this.testRunServiceFactory = testRunServiceFactory;
        this.lockfiles = lockfiles;
        this.toolchainOptions = toolchainOptions;
        this.spec = spec;
    }

    void compileWorkspace(
            Path projectRoot,
            Path cacheRoot,
            boolean all,
            List<String> members,
            List<String> memberGroups,
            TimingRecorder timings,
            ProgressWriter progress) {
        CommandToolchainOptions.WorkspaceCommandToolchains workspaceToolchains =
                toolchainOptions.workspaceTestToolchains(
                        testRunServiceFactory,
                        "test");
        WorkspaceTestService projectWorkspaceTestService = workspaceTestService.withMemberServices(
                workspaceToolchains.mainCheckers(),
                workspaceToolchains.testRunServices());
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        WorkspaceTestCompileResult result = WorkspaceMutationLock.withWorkspaceLock(
                projectRoot,
                () -> {
                    var target = lockfiles.requireFreshWorkspaceLockfile(timings, projectRoot, cacheRoot, false);
                    progress.start("Compiling workspace tests");
                    return timings.measure(
                            "compile workspace tests",
                            () -> {
                                WorkspaceBuildPlan plan = timings.measure(
                                        "plan workspace tests",
                                        () -> projectWorkspaceTestService.planTests(
                                                target,
                                                cacheRoot,
                                                CommandWorkspaceSelections.from(all, members, memberGroups)),
                                        CommandBuildAttributes::workspaceBuildPlan);
                                WorkspaceBuildResult buildResult = timings.measure(
                                        "build workspace test inputs",
                                        () -> projectWorkspaceTestService.buildTestCompileInputs(plan, cacheRoot),
                                        build -> CommandBuildAttributes.workspaceBuild(build, plan.selection()));
                                return timings.measure(
                                        "compile workspace test members",
                                        () -> projectWorkspaceTestService.compileTests(plan, buildResult),
                                        CommandTestAttributes::workspaceTestCompile);
                            },
                            CommandTestAttributes::workspaceTestCompile);
                });
        if (result.resolvedLockfile()) {
            output.detail("Resolved workspace dependencies because zolt.lock was missing");
        }
        for (WorkspaceTestCompileResult.MemberTestCompileResult member : result.members()) {
            output.success("Tests compiled in " + member.member());
        }
        int compiledMembers = result.members().size();
        String summary = compiledMembers < result.totalMemberCount()
                ? "Compiled tests for " + compiledMembers + " of " + result.totalMemberCount()
                        + " workspace members; use --all to compile every member"
                : "Compiled tests for " + compiledMembers + " workspace members";
        output.summary(summary, result.testSourceCount() + " test source files");
        output.provenance(CommandBuildProvenance.read(projectRoot));
        progress.result("Compiled tests for " + compiledMembers + " workspace members");
    }

    void compileSingle(
            Path projectRoot,
            Path cacheRoot,
            boolean noBuildCache,
            TimingRecorder timings,
            ProgressWriter progress) {
        ProjectConfig config = timings.measure(
                "config read",
                () -> projectLoader.load(projectRoot));
        var compileChecker = toolchainOptions.jdkChecker(projectRoot, config, "test");
        TestRunService projectTestRunService = testRunServiceFactory.create(
                        compileChecker,
                        toolchainOptions.testRuntimeRunChecker(projectRoot, config, compileChecker))
                .withBuildCache(CommandBuildCache.service(noBuildCache, false));
        var artifactIndex = lockfiles.requireFreshLockfile(projectRoot, config, cacheRoot, false);
        progress.start("Compiling tests");
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        output.work("Compiling tests for " + config.project().name());
        TestCompileResult result = timings.measure(
                "compile tests",
                () -> {
                    BuildResultWithClasspaths buildResult = timings.measure(
                            "build test inputs",
                            () -> projectTestRunService.buildTestInputs(
                                    projectRoot, config, cacheRoot, artifactIndex),
                            value -> CommandBuildAttributes.build(value.buildResult()));
                    return timings.measure(
                            "compile test sources",
                            () -> projectTestRunService.compileTests(projectRoot, config, buildResult),
                            CommandTestAttributes::testCompile);
                },
                CommandTestAttributes::testCompile);
        output.summary("Tests compiled", result.sourceCount() + " test source files");
        output.provenance(CommandBuildProvenance.read(projectRoot));
        progress.result("Compiled tests");
    }
}
