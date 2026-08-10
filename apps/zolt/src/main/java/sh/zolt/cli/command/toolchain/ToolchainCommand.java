package sh.zolt.cli.command.toolchain;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.net.CommandNetwork;
import sh.zolt.config.UserGlobalConfigException;
import sh.zolt.config.UserGlobalConfigParser;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.error.ActionableException;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toolchain.JavaToolchainStatus;
import sh.zolt.toolchain.JavaToolchainStatusService;
import sh.zolt.toolchain.TestRuntimeToolchainResolver;
import sh.zolt.toolchain.ToolchainConfigReader;
import sh.zolt.toolchain.ToolchainSyncResult;
import sh.zolt.toolchain.ToolchainSyncService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "toolchain",
        description = "Inspect and manage Java toolchains.",
        subcommands = {
                ToolchainCommand.StatusCommand.class,
                ToolchainCommand.SyncCommand.class,
                ToolchainInstallCommand.class,
                ToolchainListCommand.class,
                ToolchainAvailableCommand.class,
                GlobalToolchainCommand.class
        })
public final class ToolchainCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "status", description = "Show the Java toolchain Zolt would use.")
    public static final class StatusCommand implements Callable<Integer> {
        enum Format {
            TEXT,
            JSON
        }

        private final ZoltTomlParser tomlParser;
        private final ToolchainConfigReader toolchainConfigReader;
        private final UserGlobalConfigParser globalConfigParser;
        private final JavaToolchainStatusService statusService;

        @Mixin
        private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

        @Option(names = "--global", description = "Show the user global Java toolchain default.")
        private boolean global;

        @Option(names = "--config", hidden = true)
        private Path globalConfigPath = GlobalToolchainPaths.defaultConfigPath();

        @Option(names = "--json", description = "Write machine-readable JSON status.")
        private boolean json;

        @Option(names = "--format", description = "Output format: text or json.")
        private Format format = Format.TEXT;

        @Option(names = "--target", hidden = true)
        private String target;

        @Option(names = "--install-root", hidden = true)
        private Path installRoot;

        @Spec
        private CommandSpec spec;

        public StatusCommand() {
            this(
                    new ZoltTomlParser(),
                    new ToolchainConfigReader(),
                    new UserGlobalConfigParser(),
                    new JavaToolchainStatusService());
        }

        StatusCommand(
                ZoltTomlParser tomlParser,
                ToolchainConfigReader toolchainConfigReader,
                UserGlobalConfigParser globalConfigParser,
                JavaToolchainStatusService statusService) {
            this.tomlParser = tomlParser;
            this.toolchainConfigReader = toolchainConfigReader;
            this.globalConfigParser = globalConfigParser;
            this.statusService = statusService;
        }

        @Override
        public Integer call() {
            try {
                validateFormatOptions();
                Path projectRoot = projectDirectory.path();
                JavaToolchainStatus status = global
                        ? globalStatus()
                        : projectStatus(projectRoot);
                print(status);
                if (!global && !jsonOutput()) {
                    printTestRuntimeStatus(projectRoot);
                }
                if (!status.ok()) {
                    if (!jsonOutput()) {
                        CommandHumanOutput errors = CommandHumanOutput.errors(spec);
                        for (String problem : status.resolved().problems()) {
                            errors.error(problem);
                        }
                    }
                    return 1;
                }
                return 0;
            } catch (ActionableException | UserGlobalConfigException | ZoltConfigException exception) {
                throw CommandFailures.userWithCommandId(spec, machineCommandId(), exception);
            }
        }

        private JavaToolchainStatus projectStatus(Path projectRoot) {
            JavaToolchainRequest configured = toolchainConfigReader
                    .readJava(projectRoot.resolve("zolt.toml"))
                    .orElse(null);
            if (configured != null) {
                return statusService.status(
                        configured,
                        "[toolchain.java]",
                        projectRoot.resolve("zolt.lock"),
                        HostPlatform.parse(target),
                        new ToolchainStore(installRoot));
            }
            ProjectConfig config = tomlParser.parse(projectRoot.resolve("zolt.toml"));
            return statusService.status(
                    projectRoot,
                    config,
                    HostPlatform.parse(target),
                    new ToolchainStore(installRoot));
        }

        private void printTestRuntimeStatus(Path projectRoot) {
            if (toolchainConfigReader.readJavaTest(projectRoot.resolve("zolt.toml")).isEmpty()) {
                return;
            }
            ProjectConfig config = tomlParser.parse(projectRoot.resolve("zolt.toml"));
            new TestRuntimeToolchainResolver()
                    .resolve(
                            projectRoot,
                            projectRoot,
                            config,
                            HostPlatform.parse(target),
                            new ToolchainStore(installRoot))
                    .ifPresent(testRuntime -> ToolchainStatusOutput.printTestRuntime(spec, testRuntime));
        }

        private JavaToolchainStatus globalStatus() {
            JavaToolchainRequest request = GlobalToolchainCommand.globalRequest(
                    globalConfigParser.read(globalConfigPath));
            return statusService.status(
                    request,
                    "global default",
                    GlobalToolchainPaths.lockfile(globalConfigPath),
                    HostPlatform.parse(target),
                    new ToolchainStore(installRoot));
        }

        private void print(JavaToolchainStatus status) {
            if (jsonOutput()) {
                CommandOutput.printAndFlush(spec, ToolchainStatusJsonFormatter.json(machineCommandId(), status));
            } else {
                ToolchainStatusOutput.print(spec, status);
            }
        }

        private void validateFormatOptions() {
            if (json && spec.commandLine().getParseResult().hasMatchedOption("--format")) {
                throw new ActionableException(
                        "--json and --format cannot be used together.",
                        "Use --json or --format json, but not both.");
            }
        }

        private String machineCommandId() {
            return global ? "toolchain global status" : "toolchain status";
        }

        private boolean jsonOutput() {
            return json || format == Format.JSON;
        }
    }

    @Command(name = "sync", description = "Install and lock the Java toolchain for this platform.")
    public static final class SyncCommand implements Callable<Integer> {
        private final ToolchainConfigReader toolchainConfigReader;
        private final UserGlobalConfigParser globalConfigParser;
        private final ToolchainSyncService syncService;

        @Mixin
        private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

        @Option(names = "--global", description = "Install and lock the user global Java toolchain default.")
        private boolean global;

        @Option(names = "--config", hidden = true)
        private Path globalConfigPath = GlobalToolchainPaths.defaultConfigPath();

        @Option(names = "--target", hidden = true)
        private String target;

        @Option(names = "--install-root", hidden = true)
        private Path installRoot;

        @Option(
                names = "--refresh",
                description = "Resolve the newest GA patch for each configured Java feature version.")
        private boolean refresh;

        @Spec
        private CommandSpec spec;

        public SyncCommand() {
            this(new ToolchainConfigReader(), new UserGlobalConfigParser(), null);
        }

        SyncCommand(
                ToolchainConfigReader toolchainConfigReader,
                UserGlobalConfigParser globalConfigParser,
                ToolchainSyncService syncService) {
            this.toolchainConfigReader = toolchainConfigReader;
            this.globalConfigParser = globalConfigParser;
            this.syncService = syncService;
        }

        private ToolchainSyncService syncService() {
            return syncService != null ? syncService : CommandNetwork.toolchainSyncService(globalConfigPath);
        }

        @Override
        public Integer call() {
            try {
                Path projectRoot = projectDirectory.path();
                ToolchainSyncResult result = global
                        ? syncGlobal()
                        : WorkspaceMutationLock.withLockIfWorkspace(
                                projectRoot,
                                () -> syncProject(projectRoot));
                print(result);
                return 0;
            } catch (ActionableException | UserGlobalConfigException | ZoltConfigException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }

        private ToolchainSyncResult syncProject(Path projectRoot) {
            JavaToolchainRequest request = toolchainConfigReader
                    .readJava(projectRoot.resolve("zolt.toml"))
                    .orElseThrow(() -> new ActionableException(
                            "Toolchain sync needs an explicit [toolchain.java] table.",
                            "Add [toolchain.java] with version, distribution, and features, then rerun `zolt toolchain sync`."));
            return syncService().sync(
                    request,
                    toolchainConfigReader.readJavaTest(projectRoot.resolve("zolt.toml")),
                    projectRoot.resolve("zolt.lock"),
                    HostPlatform.parse(target),
                    new ToolchainStore(installRoot),
                    refresh);
        }

        private ToolchainSyncResult syncGlobal() {
            JavaToolchainRequest request = GlobalToolchainCommand.globalRequest(
                    globalConfigParser.read(globalConfigPath));
            return syncService().sync(
                    request,
                    GlobalToolchainPaths.lockfile(globalConfigPath),
                    HostPlatform.parse(target),
                    new ToolchainStore(installRoot),
                    refresh);
        }

        private void print(ToolchainSyncResult result) {
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            output.summary("Synced Java toolchain");
            output.pointer("wrote", result.lockfile().toString());
            output.pointer("planned", result.locked().id() + " for " + result.locked().platform().id());
            output.pointer("installed", result.installPath().toString());
            if (result.downloaded()) {
                output.success("Installed managed Java toolchain");
            } else if (result.installed()) {
                output.success("Managed Java toolchain is already installed");
            }
        }
    }
}
