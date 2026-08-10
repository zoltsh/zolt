package sh.zolt.cli.command.toolchain;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.config.UserGlobalConfig;
import sh.zolt.config.UserGlobalConfigEditor;
import sh.zolt.config.UserGlobalConfigException;
import sh.zolt.config.UserGlobalConfigParser;
import sh.zolt.error.ActionableException;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.JavaToolchainStatus;
import sh.zolt.toolchain.JavaToolchainStatusService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "global",
        description = "Manage user global Java toolchain defaults.",
        subcommands = {
                GlobalToolchainCommand.UseCommand.class,
                GlobalToolchainCommand.StatusCommand.class,
                GlobalToolchainCommand.UnsetCommand.class
        })
public final class GlobalToolchainCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "use", description = "Set a user global toolchain default.", subcommands = {UseJavaCommand.class})
    public static final class UseCommand implements Runnable {
        @Spec
        private CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }
    }

    @Command(name = "java", description = "Set the user global Java toolchain default.")
    public static final class UseJavaCommand implements Callable<Integer> {
        private final UserGlobalConfigEditor editor;

        @Parameters(index = "0", paramLabel = "VERSION", description = "Java feature version, such as 21.")
        private String version;

        @Option(names = "--temurin", description = "Use Eclipse Temurin.")
        private boolean temurin;

        @Option(names = "--graalvm", description = "Use GraalVM Community.")
        private boolean graalvm;

        @Option(names = "--native-image", description = "Require native-image in the global Java toolchain.")
        private boolean nativeImage;

        @Option(names = "--policy", hidden = true)
        private String policy = ToolchainPolicy.PREFER_MANAGED.id();

        @Option(names = "--config", hidden = true)
        private Path configPath = GlobalToolchainPaths.defaultConfigPath();

        @Spec
        private CommandSpec spec;

        public UseJavaCommand() {
            this(new UserGlobalConfigEditor());
        }

        UseJavaCommand(UserGlobalConfigEditor editor) {
            this.editor = editor;
        }

        @Override
        public Integer call() {
            try {
                JavaToolchainRequest request = request();
                editor.setJavaToolchainDefault(configPath, request);
                CommandHumanOutput output = CommandHumanOutput.of(spec);
                output.summary("Set global Java toolchain default");
                output.pointer("wrote", configPath.toAbsolutePath().normalize().toString());
                output.pointer("java", request.distributionLabel() + " " + request.version());
                if (!request.features().isEmpty()) {
                    output.pointer("features", request.featuresLabel());
                }
                return 0;
            } catch (ActionableException | UserGlobalConfigException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }

        private JavaToolchainRequest request() {
            return JavaToolchainRequestOptions.javaRequest(
                    version,
                    temurin,
                    graalvm,
                    nativeImage,
                    policy,
                    "the global default",
                    "global Java toolchain");
        }
    }

    @Command(name = "status", description = "Show the user global Java toolchain default.")
    public static final class StatusCommand implements Callable<Integer> {
        enum Format {
            TEXT,
            JSON
        }

        private final UserGlobalConfigParser parser;
        private final JavaToolchainStatusService statusService;

        @Option(names = "--config", hidden = true)
        private Path configPath = GlobalToolchainPaths.defaultConfigPath();

        @Option(names = "--target", hidden = true)
        private String target;

        @Option(names = "--install-root", hidden = true)
        private Path installRoot;

        @Option(names = "--json", description = "Write machine-readable JSON status.")
        private boolean json;

        @Option(names = "--format", description = "Output format: text or json.")
        private Format format = Format.TEXT;

        @Spec
        private CommandSpec spec;

        public StatusCommand() {
            this(new UserGlobalConfigParser(), new JavaToolchainStatusService());
        }

        StatusCommand(
                UserGlobalConfigParser parser,
                JavaToolchainStatusService statusService) {
            this.parser = parser;
            this.statusService = statusService;
        }

        @Override
        public Integer call() {
            try {
                JavaToolchainRequest request = globalRequest(parser.read(configPath));
                JavaToolchainStatus status = statusService.status(
                        request,
                        "global default",
                        GlobalToolchainPaths.lockfile(configPath),
                        HostPlatform.parse(target),
                        new ToolchainStore(installRoot));
                print(status);
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
            } catch (ActionableException | UserGlobalConfigException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }

        private void print(JavaToolchainStatus status) {
            if (jsonOutput()) {
                CommandOutput.printAndFlush(
                        spec,
                        ToolchainStatusJsonFormatter.json("toolchain global status", status));
            } else {
                ToolchainStatusOutput.print(spec, status);
            }
        }

        private boolean jsonOutput() {
            return json || format == Format.JSON;
        }
    }

    @Command(name = "unset", description = "Remove the user global Java toolchain default.")
    public static final class UnsetCommand implements Callable<Integer> {
        private final UserGlobalConfigEditor editor;

        @Option(names = "--config", hidden = true)
        private Path configPath = GlobalToolchainPaths.defaultConfigPath();

        @Spec
        private CommandSpec spec;

        public UnsetCommand() {
            this(new UserGlobalConfigEditor());
        }

        UnsetCommand(UserGlobalConfigEditor editor) {
            this.editor = editor;
        }

        @Override
        public Integer call() {
            try {
                editor.unsetJavaToolchainDefault(configPath);
                CommandHumanOutput output = CommandHumanOutput.of(spec);
                output.summary("Unset global Java toolchain default");
                output.pointer("updated", configPath.toAbsolutePath().normalize().toString());
                return 0;
            } catch (UserGlobalConfigException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }
    }

    static JavaToolchainRequest globalRequest(UserGlobalConfig config) {
        return config.toolchainDefaults().java().orElseThrow(() -> new ActionableException(
                "No global Java toolchain default is configured.",
                "Run `zolt toolchain global use java 21 --temurin`, or configure [defaults.toolchain.java] in "
                        + config.configPath()
                        + "."));
    }
}
