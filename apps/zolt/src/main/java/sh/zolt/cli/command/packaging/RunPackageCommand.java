package sh.zolt.cli.command.packaging;

import sh.zolt.build.BuildException;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.JavaRunException;
import sh.zolt.build.JavacException;
import sh.zolt.build.ManifestGenerationException;
import sh.zolt.build.PackageException;
import sh.zolt.build.ResourceCopyException;
import sh.zolt.build.RunPackageException;
import sh.zolt.build.run.RunPackageResult;
import sh.zolt.build.run.RunPackageService;
import sh.zolt.build.SourceDiscoveryException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.ZoltCli;
import sh.zolt.cli.command.*;
import sh.zolt.cli.command.CommandServiceBundles.CommandRunPackageServices;
import sh.zolt.cli.command.build.CommandBuildAttributes;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.perf.TimingRecorder;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.packaging.WorkspacePackageResult;
import sh.zolt.workspace.packaging.WorkspaceRunPackageResult;
import sh.zolt.workspace.packaging.WorkspaceRunPackageService;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "run-package", description = "Run a packaged jar with runtime dependencies.")
public final class RunPackageCommand implements Runnable {
    private final ManifestProjectLoader projectLoader;
    private final RunPackageService runPackageService;
    private final WorkspaceRunPackageService workspaceRunPackageService;
    private final CommandLockfiles lockfiles;

    @Parameters(arity = "0..*", paramLabel = "ARGS", description = "Arguments passed to the application after `--`.")
    private List<String> arguments = List.of();

    @Option(names = "--workspace", description = "Run packaged workspace members in dependency order.")
    private boolean workspace;

    @Option(names = "--all", description = "Select every workspace member.")
    private boolean all;

    @Option(names = "--member", description = "Select a workspace member by declared path. May be repeated.")
    private List<String> members = List.of();

    @Option(names = "--members", split = ",", description = "Select comma-separated workspace members by declared path.")
    private List<String> memberGroups = List.of();

    @Option(
            names = "--mode",
            description = {
                "Temporary package mode override when resolution tooling is unchanged.",
                "Persist [package].mode and run zolt resolve for Spring Boot transitions."
            })
    private String mode;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--cache-root", hidden = true)
    private Path cacheRoot = LocalArtifactCache.defaultRoot();

    @Mixin
    private ZoltCli.TimingOptions timingOptions = new ZoltCli.TimingOptions();

    @Spec
    private CommandSpec spec;

    public RunPackageCommand() {
        this(
                new ManifestProjectLoader(),
                CommandFrameworkServices.runPackageCommandServices(),
                new CommandLockfiles());
    }

    RunPackageCommand(
            ManifestProjectLoader projectLoader,
            CommandRunPackageServices runPackageServices,
            CommandLockfiles lockfiles) {
        this(
                projectLoader,
                runPackageServices.runPackageService(),
                runPackageServices.workspaceRunPackageService(),
                lockfiles);
    }

    RunPackageCommand(
            ManifestProjectLoader projectLoader,
            RunPackageService runPackageService,
            WorkspaceRunPackageService workspaceRunPackageService,
            CommandLockfiles lockfiles) {
        this.projectLoader = projectLoader;
        this.runPackageService = runPackageService;
        this.workspaceRunPackageService = workspaceRunPackageService;
        this.lockfiles = lockfiles;
    }

    @Override
    public void run() {
        TimingRecorder timings = CommandTimings.recorder(timingOptions);
        Path projectRoot = projectDirectory.path();
        try {
            Optional<PackageMode> packageModeOverride = PackageCommandModes.packageModeOverride(mode);
            if (workspace) {
                PackageCommandModes.rejectWorkspaceModeOverride(
                        "run-package",
                        packageModeOverride);
                runWorkspacePackages(
                        projectRoot,
                        CommandWorkspaceSelections.from(all, members, memberGroups),
                        timings,
                        packageModeOverride);
                return;
            }
            ProjectCommandContext context = timings.measure(
                    "config read",
                    () -> ProjectCommandContext.load(projectLoader, projectRoot));
            if (context.workspaceMember()) {
                // Design §4.5: the packaged runtime of a member embeds workspace outputs, so the
                // workspace builds and packages the closure and launches this member alone.
                PackageCommandModes.rejectWorkspaceModeOverride("run-package", packageModeOverride);
                runWorkspacePackages(
                        context.lockRoot(),
                        context.memberSelection(),
                        timings,
                        packageModeOverride);
                return;
            }
            runSinglePackage(context, timings, packageModeOverride);
        } catch (BuildException
                | JavacException
                | GroovyCompileException
                | JavaRunException
                | ManifestGenerationException
                | PackageException
                | ResourceCopyException
                | RunPackageException
                | SourceDiscoveryException
                | LockfileReadException
                | ResolveException
                | WorkspaceConfigException
                | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        } finally {
            CommandTimings.print(spec, "run-package", projectRoot, timingOptions, timings);
        }
    }

    private void runWorkspacePackages(
            Path workspaceRoot,
            WorkspaceSelectionRequest selection,
            TimingRecorder timings,
            Optional<PackageMode> packageModeOverride) {
        WorkspaceRunPackageResult result = timings.measure(
                "run workspace packages",
                () -> {
                    sh.zolt.workspace.packaging.WorkspaceRunPackageSnapshot snapshot =
                            sh.zolt.workspace.service.WorkspaceMutationLock.withWorkspaceLock(
                                    workspaceRoot,
                                    () -> {
                                        var target = lockfiles.requireFreshWorkspaceLockfile(
                                                timings, workspaceRoot, cacheRoot, false);
                                        WorkspaceBuildPlan plan = timings.measure(
                                                "plan workspace run packages",
                                                () -> workspaceRunPackageService.planRunPackages(
                                                        target,
                                                        cacheRoot,
                                                        selection),
                                                CommandBuildAttributes::workspaceBuildPlan);
                                        WorkspaceBuildResult buildResult = timings.measure(
                                                "build workspace run-package inputs",
                                                () -> workspaceRunPackageService.buildRunPackageInputs(
                                                        plan,
                                                        cacheRoot),
                                                CommandBuildAttributes::workspaceBuild);
                                        WorkspacePackageResult packageResult = timings.measure(
                                                "assemble workspace run packages",
                                                () -> workspaceRunPackageService.packageRunPackageInputs(
                                                        plan,
                                                        buildResult,
                                                        cacheRoot,
                                                        packageModeOverride),
                                                CommandPackageAttributes::workspacePackage);
                                        return workspaceRunPackageService.snapshotRunPackages(
                                                plan,
                                                packageResult);
                                    });
                    try (snapshot) {
                        return timings.measure(
                                "launch workspace packages",
                                () -> workspaceRunPackageService.runSnapshot(
                                        snapshot,
                                        arguments),
                                CommandRunPackageAttributes::workspaceRunPackage);
                    }
                },
                CommandRunPackageAttributes::workspaceRunPackage);
        CommandHumanOutput humanOutput = CommandHumanOutput.of(spec);
        if (result.resolvedLockfile()) {
            humanOutput.success("Resolved workspace dependencies because zolt.lock was missing");
        }
        for (WorkspaceRunPackageResult.MemberRunPackageResult member : result.members()) {
            String output = member.result().javaRunResult().output();
            CommandOutput.printAndFlush(spec, output);
            if (!output.isEmpty() && !output.endsWith("\n")) {
                humanOutput.blankLine();
            }
            humanOutput.summary("Ran packaged "
                    + member.result().javaRunResult().mainClass()
                    + " in "
                    + member.member());
            humanOutput.pointer("from", member.result().packageResult().jarPath().toString());
        }
    }

    private void runSinglePackage(
            ProjectCommandContext context,
            TimingRecorder timings,
            Optional<PackageMode> packageModeOverride) {
        Path projectRoot = context.projectRoot();
        ProjectConfig config = PackageCommandModes.withPackageModeOverride(
                context.config(),
                packageModeOverride);
        var artifactIndex = lockfiles.requireFreshLockfile(context, cacheRoot, false);
        RunPackageResult result = timings.measure(
                "run packaged application",
                () -> runPackageService.runPackage(
                        projectRoot,
                        config,
                        cacheRoot,
                        arguments,
                        artifactIndex),
                CommandRunPackageAttributes::runPackage);
        String output = result.javaRunResult().output();
        CommandOutput.printAndFlush(spec, output);
        CommandHumanOutput humanOutput = CommandHumanOutput.of(spec);
        if (!output.isEmpty() && !output.endsWith("\n")) {
            humanOutput.blankLine();
        }
        humanOutput.summary("Ran packaged " + result.javaRunResult().mainClass());
        humanOutput.pointer("from", result.packageResult().jarPath().toString());
    }
}
