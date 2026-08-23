package sh.zolt.cli.command.nativeimage;

import sh.zolt.build.BuildException;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.JavacException;
import sh.zolt.build.ManifestGenerationException;
import sh.zolt.build.PackageException;
import sh.zolt.build.ResourceCopyException;
import sh.zolt.build.SourceDiscoveryException;
import sh.zolt.build.nativeimage.NativeBuildResult;
import sh.zolt.build.nativeimage.NativeBuildService;
import sh.zolt.build.nativeimage.NativePackagePolicy;
import sh.zolt.build.NativeImageException;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.CommandProgress;
import sh.zolt.cli.command.CommandBuildProvenance;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandFrameworkServices;
import sh.zolt.cli.command.CommandLockfiles;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandServiceBundles.CommandNativeServices;
import sh.zolt.cli.command.CommandWorkspaceSelections;
import sh.zolt.cli.command.ProjectCommandContext;
import sh.zolt.cli.command.toolchain.CommandJavaToolchainJdkChecker;
import sh.zolt.cli.console.ProgressWriter;
import sh.zolt.error.ActionableException;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectVersionOverride;
import sh.zolt.resolve.ResolveException;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.toolchain.JavaToolchainExecutionService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.packaging.WorkspaceNativeBuildResult;
import sh.zolt.workspace.packaging.WorkspaceNativeBuildService;
import sh.zolt.workspace.service.WorkspaceJdkCheckerResolver;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "native",
        description = "Build a native binary with GraalVM Native Image.")
public final class NativeCommand implements Runnable {
    private final ManifestProjectLoader projectLoader;
    private final NativeBuildService nativeBuildService;
    private final WorkspaceNativeBuildService workspaceNativeBuildService;
    private final CommandLockfiles lockfiles;
    private final JavaToolchainExecutionService toolchains;

    @Option(names = "--native-image", description = "Path to the native-image executable.")
    private Path nativeImageExecutable;

    @Option(names = "--toolchain-target", hidden = true)
    private String toolchainTarget;

    @Option(names = "--toolchain-install-root", hidden = true)
    private Path toolchainInstallRoot;

    @Option(names = "--workspace", description = "Build native binaries for selected workspace members.")
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
    private Path cacheRoot = sh.zolt.cache.LocalArtifactCache.defaultRoot();

    @Spec
    private CommandSpec spec;

    public NativeCommand() {
        this(CommandFrameworkServices.nativeCommandServices());
    }

    private NativeCommand(CommandNativeServices services) {
        this(
                services.projectLoader(),
                services.nativeBuildService(),
                services.workspaceNativeBuildService(),
                new CommandLockfiles(),
                new JavaToolchainExecutionService());
    }

    NativeCommand(
            ManifestProjectLoader projectLoader,
            NativeBuildService nativeBuildService,
            WorkspaceNativeBuildService workspaceNativeBuildService,
            CommandLockfiles lockfiles,
            JavaToolchainExecutionService toolchains) {
        this.projectLoader = projectLoader;
        this.nativeBuildService = nativeBuildService;
        this.workspaceNativeBuildService = workspaceNativeBuildService;
        this.lockfiles = lockfiles;
        this.toolchains = toolchains;
    }

    @Override
    public void run() {
        ProgressWriter progress = CommandProgress.human(spec);
        Path projectRoot = projectDirectory.path();
        try {
            if (workspace) {
                buildWorkspaceNative(
                        progress,
                        projectRoot,
                        CommandWorkspaceSelections.from(all, members, memberGroups),
                        "zolt native --workspace");
                return;
            }
            ProjectCommandContext context = ProjectCommandContext.load(projectLoader, projectRoot);
            if (context.workspaceMember()) {
                // Design §4.5: a native image embeds the member's whole runtime closure, workspace
                // outputs included, so the workspace builds that closure before native-image runs.
                buildWorkspaceNative(
                        progress,
                        context.lockRoot(),
                        context.memberSelection(),
                        "zolt native");
                return;
            }
            ProjectConfig config = ProjectVersionOverride.apply(context.config());
            var artifactIndex = lockfiles.requireFreshLockfile(
                    context,
                    cacheRoot,
                    false,
                    "zolt native");
            progress.start("Building native image");
            NativeBuildResult result = nativeBuildService.buildNative(
                    projectRoot,
                    config,
                    cacheRoot,
                    resolvedNativeImage(context, config),
                    nativeImageProgress(progress),
                    artifactIndex);
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            if (result.packageResult().buildResult().resolvedLockfile()) {
                output.success("Resolved dependencies because zolt.lock was missing");
            }
            output.summary("Built native binary");
            output.pointer("wrote", result.nativeImageResult().outputBinary().toString());
            output.pointer("logged", result.nativeImageResult().logFile().toString());
            printSpringBootAotEvidence(result, "");
            output.provenance(CommandBuildProvenance.read(projectRoot));
            progress.result("Built native binary at " + result.nativeImageResult().outputBinary());
        } catch (BuildException
                | JavacException
                | GroovyCompileException
                | ManifestGenerationException
                | NativeImageException
                | PackageException
                | ResourceCopyException
                | SourceDiscoveryException
                | ActionableException
                | LockfileReadException
                | ResolveException
                | WorkspaceConfigException
                | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    /**
     * Builds native images for {@code selection} through the workspace. Reached both by
     * {@code --workspace} and by a member-directory native build.
     */
    private void buildWorkspaceNative(
            ProgressWriter progress,
            Path workspaceRoot,
            sh.zolt.workspace.service.WorkspaceSelectionRequest selection,
            String retryCommand) {
        WorkspaceNativeBuildService projectWorkspaceNativeBuildService =
                workspaceNativeBuildService.withJdkCheckers(workspaceJdkCheckers());
        WorkspaceNativeBuildResult result = WorkspaceMutationLock.withWorkspaceLock(
                workspaceRoot,
                () -> {
                    var target = lockfiles.requireFreshWorkspacePlanTarget(
                            workspaceRoot,
                            cacheRoot,
                            false,
                            retryCommand);
                    progress.start("Building workspace native images");
                    return projectWorkspaceNativeBuildService.buildNative(
                            target,
                            cacheRoot,
                            selection,
                            workspaceNativeImageResolver(),
                            nativeImageProgress(progress));
                });
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        if (result.resolvedLockfile()) {
            output.success("Resolved workspace dependencies because zolt.lock was missing");
        }
        for (WorkspaceNativeBuildResult.MemberNativeBuildResult member : result.members()) {
            output.success("Built native binary in " + member.member());
            output.pointer("wrote", member.result().nativeImageResult().outputBinary().toString());
            output.pointer("logged", member.result().nativeImageResult().logFile().toString());
            printSpringBootAotEvidence(member.result(), " in " + member.member());
        }
        output.summary(
                "Built native binaries for " + result.members().size() + " workspace members",
                result.members().size() + " members");
        output.provenance(CommandBuildProvenance.read(workspaceRoot));
        progress.result("Built native binaries for " + result.members().size() + " workspace members");
    }

    /**
     * Reached only after {@link ProjectCommandContext#workspaceMember()} answered no, so the two roots
     * this states are the same directory — a standalone project owns its own lock (design §4.5). The
     * member route builds through the workspace instead, with {@link #workspaceNativeImageResolver()}.
     */
    private Path resolvedNativeImage(ProjectCommandContext context, ProjectConfig config) {
        if (nativeImageExecutable != null) {
            return nativeImageExecutable;
        }
        return toolchains.nativeImage(
                        context.projectRoot(),
                        context.lockRoot(),
                        config,
                        HostPlatform.parse(toolchainTarget),
                        new ToolchainStore(toolchainInstallRoot))
                .orElse(null);
    }

    private WorkspaceNativeBuildService.NativeImageExecutableResolver workspaceNativeImageResolver() {
        if (nativeImageExecutable != null) {
            return WorkspaceNativeBuildService.NativeImageExecutableResolver.fixed(nativeImageExecutable);
        }
        return (workspace, member, config) -> toolchains.nativeImage(
                        member.directory(),
                        workspace.root(),
                        config,
                        HostPlatform.parse(toolchainTarget),
                        new ToolchainStore(toolchainInstallRoot))
                .orElse(null);
    }

    private WorkspaceJdkCheckerResolver workspaceJdkCheckers() {
        return (workspace, member) -> CommandJavaToolchainJdkChecker.forCommand(
                member.directory(),
                workspace.root(),
                member.config(),
                toolchainTarget,
                toolchainInstallRoot,
                "native");
    }

    private void printSpringBootAotEvidence(NativeBuildResult result, String suffix) {
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        result.springBootAotEvidencePath().ifPresent(path ->
                output.pointer("wrote", path.toString()));
    }

    private static Runnable nativeImageProgress(ProgressWriter progress) {
        return () -> progress.heartbeat("Still running: Native Image");
    }
}
