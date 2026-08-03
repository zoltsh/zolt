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
import sh.zolt.cli.command.toolchain.CommandJavaToolchainJdkChecker;
import sh.zolt.cli.console.ProgressWriter;
import sh.zolt.error.ActionableException;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectVersionOverride;
import sh.zolt.resolve.ResolveException;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
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
    private final ZoltTomlParser tomlParser;
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
                services.tomlParser(),
                services.nativeBuildService(),
                services.workspaceNativeBuildService(),
                new CommandLockfiles(),
                new JavaToolchainExecutionService());
    }

    NativeCommand(
            ZoltTomlParser tomlParser,
            NativeBuildService nativeBuildService,
            WorkspaceNativeBuildService workspaceNativeBuildService,
            CommandLockfiles lockfiles,
            JavaToolchainExecutionService toolchains) {
        this.tomlParser = tomlParser;
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
                WorkspaceNativeBuildService projectWorkspaceNativeBuildService =
                        workspaceNativeBuildService.withJdkCheckers(workspaceJdkCheckers());
                WorkspaceNativeBuildResult result = WorkspaceMutationLock.withWorkspaceLock(
                        projectRoot,
                        () -> {
                            lockfiles.requireFreshWorkspaceLockfile(projectRoot, cacheRoot, false);
                            progress.start("Building workspace native images");
                            return projectWorkspaceNativeBuildService.buildNative(
                                    projectRoot,
                                    cacheRoot,
                                    CommandWorkspaceSelections.from(all, members, memberGroups),
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
                output.provenance(CommandBuildProvenance.read(projectRoot));
                progress.result("Built native binaries for " + result.members().size() + " workspace members");
                return;
            }
            ProjectConfig config = ProjectVersionOverride.apply(
                    tomlParser.parse(projectRoot.resolve("zolt.toml")));
            progress.start("Building native image");
            NativeBuildResult result = nativeBuildService.buildNative(
                    projectRoot,
                    config,
                    cacheRoot,
                    resolvedNativeImage(projectRoot, config),
                    nativeImageProgress(progress));
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

    private Path resolvedNativeImage(Path projectRoot, ProjectConfig config) {
        if (nativeImageExecutable != null) {
            return nativeImageExecutable;
        }
        return toolchains.nativeImage(
                        projectRoot,
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
