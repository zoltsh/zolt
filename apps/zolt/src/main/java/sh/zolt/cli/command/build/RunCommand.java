package sh.zolt.cli.command.build;

import sh.zolt.build.BuildException;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.JavaRunException;
import sh.zolt.build.JavacException;
import sh.zolt.build.ResourceCopyException;
import sh.zolt.build.RunException;
import sh.zolt.build.run.JavaRunResult;
import sh.zolt.build.run.RunResult;
import sh.zolt.build.run.RunService;
import sh.zolt.build.SourceDiscoveryException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.ZoltCli;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandFrameworkServices;
import sh.zolt.cli.command.CommandLockfiles;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandServiceBundles.CommandRunServices;
import sh.zolt.cli.command.CommandTimings;
import sh.zolt.cli.command.CommandToolchainOptions;
import sh.zolt.cli.command.CommandWorkspaceSelections;
import sh.zolt.error.ActionableException;
import sh.zolt.framework.FrameworkRunException;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.perf.TimingRecorder;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.run.WorkspaceRunResult;
import sh.zolt.workspace.run.WorkspaceRunService;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "run", description = "Build and run the configured main class.")
public final class RunCommand implements Runnable {
    private final ZoltTomlParser tomlParser;
    private final RunService runService;
    private final WorkspaceRunService workspaceRunService;
    private final CommandLockfiles lockfiles;

    @Parameters(arity = "0..*", paramLabel = "ARGS", description = "Arguments passed to the application after `--`.")
    private List<String> arguments = List.of();

    @Option(names = "--workspace", description = "Run workspace members in dependency order.")
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

    @Mixin
    private CommandToolchainOptions toolchainOptions = new CommandToolchainOptions();

    @Mixin
    private ZoltCli.TimingOptions timingOptions = new ZoltCli.TimingOptions();

    @Spec
    private CommandSpec spec;

    public RunCommand() {
        this(
                new ZoltTomlParser(),
                CommandFrameworkServices.runCommandServices(),
                new CommandLockfiles());
    }

    RunCommand(
            ZoltTomlParser tomlParser,
            CommandRunServices runServices,
            CommandLockfiles lockfiles) {
        this(
                tomlParser,
                runServices.runService(),
                runServices.workspaceRunService(),
                lockfiles);
    }

    RunCommand(
            ZoltTomlParser tomlParser,
            RunService runService,
            WorkspaceRunService workspaceRunService,
            CommandLockfiles lockfiles) {
        this.tomlParser = tomlParser;
        this.runService = runService;
        this.workspaceRunService = workspaceRunService;
        this.lockfiles = lockfiles;
    }

    @Override
    public void run() {
        TimingRecorder timings = CommandTimings.recorder(timingOptions);
        Path projectRoot = projectDirectory.path();
        try {
            if (workspace) {
                WorkspaceRunService projectWorkspaceRunService =
                        workspaceRunService.withJdkCheckers(toolchainOptions.workspaceJdkCheckers("run"));
                WorkspaceRunResult result = WorkspaceMutationLock.withWorkspaceLock(
                        projectRoot,
                        () -> {
                            lockfiles.requireFreshWorkspaceLockfile(projectRoot, cacheRoot, false);
                            return timings.measure(
                                    "run workspace",
                                    () -> {
                                        WorkspaceBuildPlan plan = timings.measure(
                                                "plan workspace run",
                                                () -> projectWorkspaceRunService.planRun(
                                                        projectRoot,
                                                        cacheRoot,
                                                        CommandWorkspaceSelections.from(
                                                                all,
                                                                members,
                                                                memberGroups)),
                                                CommandBuildAttributes::workspaceBuildPlan);
                                        WorkspaceBuildResult buildResult = timings.measure(
                                                "build workspace run inputs",
                                                () -> projectWorkspaceRunService.buildRunInputs(plan, cacheRoot),
                                                CommandBuildAttributes::workspaceBuild);
                                        return timings.measure(
                                                "launch workspace members",
                                                () -> projectWorkspaceRunService.runBuiltMembers(
                                                        plan,
                                                        buildResult,
                                                        arguments,
                                                        output -> CommandOutput.printAndFlush(spec, output)),
                                                CommandRunAttributes::workspaceRun);
                                    },
                                    CommandRunAttributes::workspaceRun);
                        });
                CommandHumanOutput output = CommandHumanOutput.of(spec);
                if (result.resolvedLockfile()) {
                    output.success("Resolved workspace dependencies because zolt.lock was missing");
                }
                for (WorkspaceRunResult.MemberRunResult member : result.members()) {
                    JavaRunResult javaRunResult = member.result().javaRunResult();
                    String processOutput = javaRunResult.output();
                    if (!processOutput.isEmpty() && !processOutput.endsWith("\n")) {
                        output.blankLine();
                    }
                    if (javaRunResult.signalled()) {
                        output.summary("Stopped "
                                + javaRunResult.mainClass()
                                + " in "
                                + member.member()
                                + " (signal "
                                + javaRunResult.signal()
                                + ")");
                    } else {
                        output.summary("Ran "
                                + javaRunResult.mainClass()
                                + " in "
                                + member.member());
                    }
                }
                return;
            }
            ProjectConfig config = timings.measure(
                    "config read",
                    () -> tomlParser.parse(projectRoot.resolve("zolt.toml")));
            lockfiles.requireFreshLockfile(projectRoot, config, cacheRoot, false);
            RunResult result = timings.measure(
                    "run application",
                    () -> runService.withJdkChecker(toolchainOptions.jdkChecker(projectRoot, config, "run")).run(
                            projectRoot,
                            config,
                            cacheRoot,
                            arguments,
                            output -> CommandOutput.printAndFlush(spec, output)),
                    CommandRunAttributes::run);
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            JavaRunResult javaRunResult = result.javaRunResult();
            String processOutput = javaRunResult.output();
            if (!processOutput.isEmpty() && !processOutput.endsWith("\n")) {
                output.blankLine();
            }
            if (javaRunResult.signalled()) {
                output.summary("Stopped "
                        + javaRunResult.mainClass()
                        + " (signal "
                        + javaRunResult.signal()
                        + ")");
            } else {
                output.summary("Ran " + javaRunResult.mainClass());
            }
        } catch (JavaRunException exception) {
            throw CommandFailures.user(spec, firstLine(exception.getMessage()), exception);
        } catch (BuildException
                | JavacException
                | GroovyCompileException
                | ResourceCopyException
                | RunException
                | FrameworkRunException
                | SourceDiscoveryException
                | ActionableException
                | LockfileReadException
                | ResolveException
                | WorkspaceConfigException
                | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        } finally {
            CommandTimings.print(spec, "run", projectRoot, timingOptions, timings);
        }
    }

    private static String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }
}
