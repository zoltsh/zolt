package sh.zolt.cli.command.packaging;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.CommandProgress;
import sh.zolt.cli.command.CommandBuildProvenance;
import sh.zolt.cli.command.CommandLockfiles;
import sh.zolt.cli.command.CommandToolchainOptions;
import sh.zolt.cli.command.build.CommandBuildAttributes;
import sh.zolt.cli.console.ProgressWriter;
import sh.zolt.perf.TimingRecorder;
import sh.zolt.project.PackageMode;
import sh.zolt.workspace.packaging.WorkspacePackageResult;
import sh.zolt.workspace.packaging.WorkspacePackageService;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.nio.file.Path;
import java.util.Optional;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Packages a member selection through the workspace: gate the root lock, plan, build the closure in
 * dependency order, then assemble the selected members' archives.
 *
 * <p>Reached both by {@code zolt package --workspace} and by a member-directory {@code zolt package}.
 * A member's archive embeds workspace outputs, so the closure must be built the workspace's way even
 * when only one member is being packaged (design §4.5).
 */
final class WorkspacePackageCommandRunner {
    private final WorkspacePackageService workspacePackageService;
    private final CommandPackageResultWriter packageResultWriter;
    private final CommandLockfiles lockfiles;
    private final CommandToolchainOptions toolchainOptions;
    private final CommandSpec spec;

    WorkspacePackageCommandRunner(
            WorkspacePackageService workspacePackageService,
            CommandPackageResultWriter packageResultWriter,
            CommandLockfiles lockfiles,
            CommandToolchainOptions toolchainOptions,
            CommandSpec spec) {
        this.workspacePackageService = workspacePackageService;
        this.packageResultWriter = packageResultWriter;
        this.lockfiles = lockfiles;
        this.toolchainOptions = toolchainOptions;
        this.spec = spec;
    }

    void packageMembers(
            Path workspaceRoot,
            Path cacheRoot,
            WorkspaceSelectionRequest selection,
            TimingRecorder timings,
            Optional<PackageMode> packageModeOverride) {
        ProgressWriter progress = CommandProgress.human(spec);
        WorkspacePackageService projectWorkspacePackageService =
                workspacePackageService.withJdkCheckers(toolchainOptions.workspaceJdkCheckers("package"));
        WorkspacePackageResult result = WorkspaceMutationLock.withWorkspaceLock(workspaceRoot, () -> {
            var target = lockfiles.requireFreshWorkspaceLockfile(timings, workspaceRoot, cacheRoot, false);
            progress.start("Packaging workspace");
            return timings.measure(
                    "package workspace",
                    () -> {
                        WorkspaceBuildPlan plan = timings.measure(
                                "plan workspace packages",
                                () -> projectWorkspacePackageService.planPackages(
                                        target,
                                        cacheRoot,
                                        selection),
                                CommandBuildAttributes::workspaceBuildPlan);
                        WorkspaceBuildResult buildResult = timings.measure(
                                "build workspace package inputs",
                                () -> projectWorkspacePackageService.buildPackageInputs(plan, cacheRoot),
                                CommandBuildAttributes::workspaceBuild);
                        return timings.measure(
                                "assemble workspace packages",
                                () -> projectWorkspacePackageService.packageBuiltJars(
                                        plan,
                                        buildResult,
                                        cacheRoot,
                                        packageModeOverride),
                                CommandPackageAttributes::workspacePackage);
                    },
                    CommandPackageAttributes::workspacePackage);
        });
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        if (result.resolvedLockfile()) {
            output.success("Resolved workspace dependencies because zolt.lock was missing");
        }
        for (WorkspacePackageResult.MemberPackageResult member : result.members()) {
            packageResultWriter.print(output, member.result(), " in " + member.member());
        }
        String workspaceSummary = CommandPackageResultWriter.workspaceSummary(result);
        output.success(workspaceSummary);
        output.provenance(CommandBuildProvenance.read(workspaceRoot));
        progress.result(workspaceSummary);
    }
}
