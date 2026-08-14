package sh.zolt.cli.command.build;

import sh.zolt.build.BuildException;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.JavacException;
import sh.zolt.build.ResourceCopyException;
import sh.zolt.build.SourceDiscoveryException;
import sh.zolt.cache.ArtifactCacheException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.CommandProgress;
import sh.zolt.cli.ZoltCli;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandBuildProvenance;
import sh.zolt.cli.command.CommandFrameworkServices;
import sh.zolt.cli.command.CommandLockfiles;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandServiceBundles.CommandBuildServices;
import sh.zolt.cli.command.CommandTimings;
import sh.zolt.cli.command.CommandToolchainOptions;
import sh.zolt.cli.command.CommandWorkspaceSelections;
import sh.zolt.cli.console.ProgressWriter;
import sh.zolt.error.ActionableException;
import sh.zolt.framework.FrameworkBuildAugmentationResult;
import sh.zolt.framework.FrameworkBuildAugmenter;
import sh.zolt.framework.FrameworkBuildException;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.perf.TimingRecorder;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceBuildService;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import sh.zolt.workspace.WorkspaceConfigException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "build", description = "Compile main Java sources with the resolved compile classpath.")
public final class BuildCommand implements Runnable {
    private final ZoltTomlParser tomlParser;
    private final BuildService buildService;
    private final WorkspaceBuildService workspaceBuildService;
    private final FrameworkBuildAugmenter frameworkBuildAugmenter;
    private final CommandLockfiles lockfiles;

    @Option(names = "--offline", description = "Use only artifacts already present in the local cache.")
    private boolean offline;

    @Option(names = "--workspace", description = "Build workspace members in dependency order.")
    private boolean workspace;

    @Option(names = "--all", description = "Select every workspace member.")
    private boolean all;

    @Option(names = "--member", description = "Select a workspace member by declared path. May be repeated.")
    private List<String> members = List.of();

    @Option(names = "--members", split = ",", description = "Select comma-separated workspace members by declared path.")
    private List<String> memberGroups = List.of();

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

    public BuildCommand() {
        this(CommandFrameworkServices.buildCommandServices());
    }

    private BuildCommand(CommandBuildServices services) {
        this(
                new ZoltTomlParser(),
                services.buildService(),
                services.workspaceBuildService(),
                services.frameworkBuildAugmenter(),
                new CommandLockfiles());
    }

    BuildCommand(
            ZoltTomlParser tomlParser,
            BuildService buildService,
            WorkspaceBuildService workspaceBuildService,
            FrameworkBuildAugmenter frameworkBuildAugmenter,
            CommandLockfiles lockfiles) {
        this.tomlParser = tomlParser;
        this.buildService = buildService;
        this.workspaceBuildService = workspaceBuildService;
        this.frameworkBuildAugmenter = frameworkBuildAugmenter;
        this.lockfiles = lockfiles;
    }

    @Override
    public void run() {
        TimingRecorder timings = CommandTimings.recorder(timingOptions);
        ProgressWriter progress = CommandProgress.human(spec);
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        Path projectRoot = projectDirectory.path();
        CommandBuildCacheSupport buildCache = CommandBuildCacheSupport.create(noBuildCache, offline);
        try {
            if (workspace) {
                WorkspaceBuildService projectWorkspaceBuildService = buildCache.applyTo(
                        workspaceBuildService.withJdkCheckers(toolchainOptions.workspaceJdkCheckers("build")));
                WorkspaceBuildResult result = WorkspaceMutationLock.withWorkspaceLock(
                        projectRoot,
                        () -> {
                            var target = lockfiles.requireFreshWorkspaceLockfile(
                                    timings, projectRoot, cacheRoot, offline, "zolt build --workspace");
                            progress.start("Building workspace");
                            return timings.measure(
                                    "build workspace",
                                    () -> {
                                        WorkspaceBuildPlan plan = timings.measure(
                                                "plan workspace build",
                                                () -> projectWorkspaceBuildService.planBuild(
                                                        target,
                                                        cacheRoot,
                                                        offline,
                                                        CommandWorkspaceSelections.from(
                                                                all,
                                                                members,
                                                                memberGroups)),
                                                CommandBuildAttributes::workspaceBuildPlan);
                                        return timings.measure(
                                                "compile workspace members",
                                                () -> projectWorkspaceBuildService.build(plan, cacheRoot),
                                                CommandBuildAttributes::workspaceBuild);
                                    },
                                    CommandBuildAttributes::workspaceBuild);
                        });
                if (result.resolvedLockfile()) {
                    output.detail("Resolved workspace dependencies because zolt.lock was missing");
                }
                for (WorkspaceBuildResult.MemberBuildResult member : result.members()) {
                    if (member.result().mainCompilationSkipped()) {
                        output.detail("Skipped main compilation in " + member.member() + "; inputs are unchanged");
                    } else if (member.result().mainCompilationRestored()) {
                        output.detail(
                                "Restored "
                                        + member.result().mainRestoredClassCount()
                                        + " main classes in "
                                        + member.member()
                                        + " (build cache)");
                    } else {
                        output.detail(
                                "Compiled "
                                        + member.result().sourceCount()
                                        + " main source files in "
                                        + member.member());
                        if (processorFullCompile(member.result())) {
                            output.detail("full compile: " + member.result().mainIncrementalFallbackReason()
                                    + " in " + member.member());
                        }
                    }
                }
                if (result.mainCompilationExecutedCount() == 0) {
                    output.detail("Skipped workspace main compilation; inputs are unchanged");
                } else {
                    output.summary(
                            "Compiled " + result.compiledSourceCount() + " workspace main source files",
                            result.members().size() + " members");
                }
                output.provenance(CommandBuildProvenance.read(projectRoot));
                progress.result("Built " + result.sourceCount() + " workspace main source files");
                return;
            }
            ProjectConfig config = timings.measure(
                    "config read",
                    () -> tomlParser.parse(projectRoot.resolve("zolt.toml")));
            var artifactIndex = lockfiles.requireFreshLockfile(
                    projectRoot, config, cacheRoot, offline, "zolt build");
            progress.start("Building project");
            output.work("Building " + config.project().name());
            BuildResult result = timings.measure(
                    "compile main",
                    () -> buildCache
                            .applyTo(buildService.withJdkChecker(
                                    toolchainOptions.jdkChecker(projectRoot, config, "build")))
                            .build(
                                    projectRoot,
                                    config,
                                    cacheRoot,
                                    offline,
                                    artifactIndex),
                    CommandBuildAttributes::build);
            if (result.resolvedLockfile()) {
                output.detail("Resolved dependencies because zolt.lock was missing");
            }
            if (result.mainCompilationSkipped()) {
                output.detail("Skipped main compilation; inputs are unchanged");
            } else if (result.mainCompilationRestored()) {
                output.summary(
                        "Restored " + result.mainRestoredClassCount() + " main classes",
                        "build cache");
            } else if (result.resourceCount() > 0) {
                output.summary(
                        "Compiled " + result.sourceCount() + " main source files",
                        result.resourceCount() + " resources");
            } else {
                output.summary("Compiled " + result.sourceCount() + " main source files");
            }
            if (processorFullCompile(result)) {
                output.detail("full compile: " + result.mainIncrementalFallbackReason());
            }
            output.pointer("wrote", result.outputDirectory().toString());
            output.provenance(CommandBuildProvenance.read(projectRoot));
            progress.result("Built " + result.sourceCount() + " main source files");
            Optional<FrameworkBuildAugmentationResult> augmentationResult =
                    timings.measure(
                            "framework augmentation",
                            () -> frameworkBuildAugmenter.augmentIfEnabled(
                                    projectRoot,
                                    config,
                                    cacheRoot),
                            CommandBuildAttributes::frameworkAugmentation);
            if (augmentationResult.isPresent()) {
                FrameworkBuildAugmentationResult augmentation = augmentationResult.orElseThrow();
                output.detail(
                        "Ran "
                                + augmentation.displayName()
                                + " augmentation; runner jar "
                                + augmentation.runnerJar());
            }
        } catch (BuildException
                | ArtifactCacheException
                | JavacException
                | GroovyCompileException
                | FrameworkBuildException
                | ResourceCopyException
                | SourceDiscoveryException
                | ActionableException
                | LockfileReadException
                | ResolveException
                | WorkspaceConfigException
                | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        } finally {
            buildCache.surfaceWarnings(output);
            CommandTimings.print(spec, "build", projectRoot, timingOptions, timings);
        }
    }

    /**
     * A main compile that went full specifically because an annotation processor is not isolating
     * (dynamic/aggregating, or missing incremental metadata). Surfaced as a subtle build detail so the
     * reason an incremental fast path was skipped is visible without reading the JSON attributes.
     */
    static boolean processorFullCompile(BuildResult result) {
        return "full".equals(result.mainCompilationMode())
                && result.mainIncrementalFallbackReason().startsWith("processor");
    }
}
