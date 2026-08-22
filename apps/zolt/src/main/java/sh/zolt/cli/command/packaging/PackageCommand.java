package sh.zolt.cli.command.packaging;

import sh.zolt.build.BuildException;
import sh.zolt.build.BuildResultWithClasspaths;
import sh.zolt.build.BuildService;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.JavacException;
import sh.zolt.build.ManifestGenerationException;
import sh.zolt.build.PackageException;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanFormatter;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.build.packaging.PackageService;
import sh.zolt.build.ResourceCopyException;
import sh.zolt.build.SourceDiscoveryException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.CommandProgress;
import sh.zolt.cli.ZoltCli;
import sh.zolt.cli.command.*;
import sh.zolt.cli.command.CommandServiceBundles.CommandPackageServices;
import sh.zolt.cli.command.build.CommandBuildAttributes;
import sh.zolt.cli.console.ProgressWriter;
import sh.zolt.error.ActionableException;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.perf.TimingRecorder;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.packaging.WorkspacePackageService;
import sh.zolt.workspace.publish.WorkspaceBomPackager;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "package", description = "Package compiled classes into a jar.")
public final class PackageCommand implements Runnable {
    private final ManifestProjectLoader projectLoader;
    private final PackagePlanService packagePlanService;
    private final PackagePlanFormatter packagePlanFormatter;
    private final PackageService packageService;
    private final BuildService buildService;
    private final WorkspacePackageService workspacePackageService;
    private final WorkspaceBomPackager bomPackager;
    private final CommandLockfiles lockfiles;
    private final CommandPackageResultWriter packageResultWriter;

    @Option(names = "--workspace", description = "Package workspace members in dependency order.")
    private boolean workspace;

    @Option(names = "--all", description = "Select every workspace member.")
    private boolean all;

    @Option(names = "--member", description = "Select a workspace member by declared path. May be repeated.")
    private List<String> members = List.of();

    @Option(names = "--members", split = ",", description = "Select comma-separated workspace members by declared path.")
    private List<String> memberGroups = List.of();

    @Option(names = "--mode", description = "Temporary package mode override when resolution tooling is unchanged. Persist [package].mode and run zolt resolve for Spring Boot transitions.")
    private String mode;

    @Option(names = "--plan", description = "Print the package content plan without building or writing the archive.")
    private boolean planOnly;

    @Option(names = "--format", description = "Package plan output format: text or json.")
    private String format = "text";

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--cache-root", hidden = true)
    private Path cacheRoot = LocalArtifactCache.defaultRoot();

    @Option(
            names = "--no-build-cache",
            description = "Bypass the build-output cache for this run (neither restore nor store).")
    private boolean noBuildCache;

    @Mixin
    private CommandToolchainOptions toolchainOptions = new CommandToolchainOptions();

    @Mixin
    private ZoltCli.TimingOptions timingOptions = new ZoltCli.TimingOptions();

    @Spec
    private CommandSpec spec;

    public PackageCommand() {
        this(
                new ManifestProjectLoader(),
                new PackagePlanFormatter(),
                CommandFrameworkServices.packageCommandServices(),
                new CommandPackageResultWriter(),
                new CommandLockfiles());
    }

    PackageCommand(
            ManifestProjectLoader projectLoader,
            PackagePlanFormatter packagePlanFormatter,
            CommandPackageServices packageServices,
            CommandPackageResultWriter packageResultWriter,
            CommandLockfiles lockfiles) {
        this(
                projectLoader,
                packageServices.packagePlanService(),
                packagePlanFormatter,
                packageServices.packageService(),
                packageServices.buildService(),
                packageServices.workspacePackageService(),
                packageResultWriter,
                lockfiles);
    }

    PackageCommand(
            ManifestProjectLoader projectLoader,
            PackagePlanService packagePlanService,
            PackagePlanFormatter packagePlanFormatter,
            PackageService packageService,
            BuildService buildService,
            WorkspacePackageService workspacePackageService,
            CommandLockfiles lockfiles) {
        this(
                projectLoader,
                packagePlanService,
                packagePlanFormatter,
                packageService,
                buildService,
                workspacePackageService,
                new CommandPackageResultWriter(),
                lockfiles);
    }

    PackageCommand(
            ManifestProjectLoader projectLoader,
            PackagePlanService packagePlanService,
            PackagePlanFormatter packagePlanFormatter,
            PackageService packageService,
            BuildService buildService,
            WorkspacePackageService workspacePackageService,
            CommandPackageResultWriter packageResultWriter,
            CommandLockfiles lockfiles) {
        this.projectLoader = projectLoader;
        this.packagePlanService = packagePlanService;
        this.packagePlanFormatter = packagePlanFormatter;
        this.packageService = packageService;
        this.buildService = buildService;
        this.workspacePackageService = workspacePackageService;
        this.bomPackager = new WorkspaceBomPackager(packagePlanService);
        this.packageResultWriter = packageResultWriter;
        this.lockfiles = lockfiles;
    }

    @Override
    public void run() {
        TimingRecorder timings = CommandTimings.recorder(timingOptions);
        Path projectRoot = projectDirectory.path();
        try {
            Optional<PackageMode> packageModeOverride = PackageCommandModes.packageModeOverride(mode);
            PackageCommandModes.PlanOutputFormat planOutputFormat = PackageCommandModes.planOutputFormat(format);
            if (!planOnly && planOutputFormat != PackageCommandModes.PlanOutputFormat.TEXT) {
                throw PackageException.actionable(
                        "Package --format is only supported with --plan.",
                        "Use `zolt package --plan --format json`.");
            }
            if (workspace) {
                if (planOnly) {
                    throw PackageException.actionable(
                            "Package --plan is currently single-project.",
                            "Run it from the member project you want to inspect.");
                }
                PackageCommandModes.rejectWorkspaceModeOverride("package", packageModeOverride);
                packageWorkspace(
                        projectRoot,
                        CommandWorkspaceSelections.from(all, members, memberGroups),
                        timings,
                        packageModeOverride);
                return;
            }
            ProjectCommandContext context = timings.measure(
                    "config read",
                    () -> ProjectCommandContext.load(projectLoader, projectRoot));
            if (context.workspaceMember() && !planOnly) {
                // Design §4.5: packaging a member packages the workspace outputs it embeds, in
                // workspace order. `--plan` stays a read-only projection of the same root lock.
                PackageCommandModes.rejectWorkspaceModeOverride("package", packageModeOverride);
                packageWorkspace(
                        context.lockRoot(),
                        context.memberSelection(),
                        timings,
                        packageModeOverride);
                return;
            }
            runSingleProjectPackage(context, timings, packageModeOverride, planOutputFormat);
        } catch (BuildException
                | JavacException
                | GroovyCompileException
                | ManifestGenerationException
                | PackageException
                | ResourceCopyException
                | SourceDiscoveryException
                | ActionableException
                | LockfileReadException
                | ResolveException
                | WorkspaceConfigException
                | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        } finally {
            CommandTimings.print(spec, "package", projectRoot, timingOptions, timings);
        }
    }

    private void packageWorkspace(
            Path workspaceRoot,
            WorkspaceSelectionRequest selection,
            TimingRecorder timings,
            Optional<PackageMode> packageModeOverride) {
        new WorkspacePackageCommandRunner(
                        workspacePackageService, packageResultWriter, lockfiles, toolchainOptions, spec)
                .packageMembers(workspaceRoot, cacheRoot, selection, timings, packageModeOverride);
    }

    private void runSingleProjectPackage(
            ProjectCommandContext context,
            TimingRecorder timings,
            Optional<PackageMode> packageModeOverride,
            PackageCommandModes.PlanOutputFormat planOutputFormat) {
        Path projectRoot = context.projectRoot();
        ProjectConfig config = PackageCommandModes.withPackageModeOverride(
                context.config(),
                packageModeOverride);
        if (!planOnly) {
            packageService.preparePackageToolingIfNeeded(projectRoot, config, cacheRoot);
        }
        // `--plan` from a member is a read-only projection of a lock this path cannot refresh — only
        // `zolt resolve` does, from either scope — so it reports the plan instead of gating (§6.8).
        var artifactIndex = planOnly && context.workspaceMember()
                ? new VerifiedArtifactIndex()
                : lockfiles.requireFreshLockfile(context, cacheRoot, false);
        if (config.packageSettings().mode() == PackageMode.BOM) {
            runSingleProjectBomPackage(context, config, artifactIndex);
            return;
        }
        if (planOnly) {
            PackagePlan packagePlan = timings.measure(
                    "plan package contents",
                    () -> packagePlanService.plan(
                            projectRoot,
                            config,
                            CommandMemberLock.authoritative(context),
                            cacheRoot),
                    CommandPackageAttributes::packagePlan);
            if (planOutputFormat == PackageCommandModes.PlanOutputFormat.JSON) {
                CommandOutput.printAndFlush(spec, packagePlanFormatter.json(packagePlan));
            } else {
                CommandOutput.printAndFlush(spec, packagePlanFormatter.text(packagePlan));
            }
            return;
        }
        ProgressWriter progress = CommandProgress.human(spec);
        progress.start("Packaging project");
        var result = timings.measure(
                "package",
                () -> {
                    BuildResultWithClasspaths buildResult = timings.measure(
                            "build package inputs",
                            () -> buildService.withJdkChecker(
                                            toolchainOptions.jdkChecker(context, config, "package"))
                                    .withBuildCache(CommandBuildCache.service(noBuildCache, false))
                                    .buildWithClasspaths(
                                    projectRoot,
                                    config,
                                    cacheRoot,
                                    false,
                                    artifactIndex),
                            resultWithClasspaths -> CommandBuildAttributes.build(resultWithClasspaths.buildResult()));
                    return timings.measure(
                            "assemble package",
                            () -> packageService.packageJar(projectRoot, config, buildResult, cacheRoot),
                            CommandPackageAttributes::packageResult);
                },
                CommandPackageAttributes::packageResult);
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        if (result.buildResult().resolvedLockfile()) {
            output.success("Resolved dependencies because zolt.lock was missing");
        }
        packageResultWriter.print(CommandHumanOutput.of(spec), result, "");
        output.provenance(CommandBuildProvenance.read(projectRoot));
        progress.result("Packaged " + result.jarPath());
    }

    private void runSingleProjectBomPackage(
            ProjectCommandContext context, ProjectConfig config, VerifiedArtifactIndex artifactIndex) {
        Path projectRoot = context.projectRoot();
        if (planOnly) {
            CommandOutput.printAndFlush(spec, "Package mode: bom\nArtifact: "
                    + config.project().name() + "-" + config.project().version()
                    + ".pom (dependencyManagement POM; no archive)\n");
            return;
        }
        ProgressWriter progress = CommandProgress.human(spec);
        progress.start("Packaging BOM");
        BuildResultWithClasspaths buildResult = buildService
                .withJdkChecker(toolchainOptions.jdkChecker(context, config, "package"))
                .withBuildCache(CommandBuildCache.service(noBuildCache, false))
                .buildWithClasspaths(projectRoot, config, cacheRoot, false, artifactIndex);
        // A standalone BOM resolves no workspace family; use zolt package --workspace for a family BOM.
        var result = bomPackager.packageStandaloneBom(projectRoot, config, buildResult.buildResult());
        packageResultWriter.print(CommandHumanOutput.of(spec), result, "");
        progress.result("Packaged " + result.jarPath());
    }
}
