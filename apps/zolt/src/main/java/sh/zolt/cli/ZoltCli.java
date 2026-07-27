package sh.zolt.cli;

import sh.zolt.cli.command.build.BuildCommand;
import sh.zolt.cli.command.build.CleanCommand;
import sh.zolt.cli.command.build.PlanCommand;
import sh.zolt.cli.command.build.RunCommand;
import sh.zolt.cli.command.config.ConfigCommand;
import sh.zolt.cli.command.config.InitCommand;
import sh.zolt.cli.command.dependency.AddCommand;
import sh.zolt.cli.command.dependency.ConflictsCommand;
import sh.zolt.cli.command.dependency.PlatformCommand;
import sh.zolt.cli.command.dependency.RemoveCommand;
import sh.zolt.cli.command.dependency.VersionCommand;
import sh.zolt.cli.command.ide.IdeCommand;
import sh.zolt.cli.command.insight.ExplainCommand;
import sh.zolt.cli.command.insight.TreeCommand;
import sh.zolt.cli.command.insight.WhyCommand;
import sh.zolt.cli.command.nativeimage.NativeCommand;
import sh.zolt.cli.command.nativeimage.NativeSmokeCommand;
import sh.zolt.cli.command.packaging.PackageCommand;
import sh.zolt.cli.command.packaging.RunPackageCommand;
import sh.zolt.cli.command.packaging.SelfParityCommand;
import sh.zolt.cli.command.publish.PublishCommand;
import sh.zolt.cli.command.publish.ReleaseArchiveCommand;
import sh.zolt.cli.command.publish.ReleaseVerifyCommand;
import sh.zolt.cli.command.quality.CheckCommand;
import sh.zolt.cli.command.quality.CoverageCommand;
import sh.zolt.cli.command.quality.DoctorCommand;
import sh.zolt.cli.command.quality.PolicyCommand;
import sh.zolt.cli.command.quarkus.QuarkusCommand;
import sh.zolt.cli.command.resolve.ClasspathCommand;
import sh.zolt.cli.command.resolve.ResolveCommand;
import sh.zolt.cli.command.selfhost.SelfCheckCommand;
import sh.zolt.cli.command.testcmd.IntegrationTestCommand;
import sh.zolt.cli.command.testcmd.TestCommand;
import sh.zolt.cli.console.ColorMode;
import sh.zolt.cli.console.ConsoleStyle;
import sh.zolt.cli.console.ProgressMode;
import sh.zolt.cli.console.ProgressPolicy;
import sh.zolt.perf.TimingFormat;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

@Command(
        name = "zolt",
        versionProvider = ZoltCli.ZoltVersionProvider.class,
        description = "The modern Java build toolkit.",
        subcommands = {
                ZoltHelpCommand.class,
                InitCommand.class,
                VersionCommand.class,
                sh.zolt.cli.command.self.SelfCommand.class,
                ConfigCommand.class,
                sh.zolt.cli.command.cache.CacheCommand.class,
                CheckCommand.class,
                AddCommand.class,
                RemoveCommand.class,
                PlatformCommand.class,
                ResolveCommand.class,
                TreeCommand.class,
                WhyCommand.class,
                PolicyCommand.class,
                ConflictsCommand.class,
                sh.zolt.cli.command.dependency.OutdatedCommand.class,
                sh.zolt.cli.command.dependency.UpdateCommand.class,
                ExplainCommand.class,
                PlanCommand.class,
                ClasspathCommand.class,
                IdeCommand.class,
                sh.zolt.cli.command.toolchain.ToolchainCommand.class,
                sh.zolt.cli.command.toolchain.ExecCommand.class,
                sh.zolt.cli.command.toolchain.ShimsCommand.class,
                QuarkusCommand.class,
                sh.zolt.cli.command.task.AliasesCommand.class,
                sh.zolt.cli.command.task.TasksCommand.class,
                sh.zolt.cli.command.task.TaskCommand.class,
                BuildCommand.class,
                RunCommand.class,
                TestCommand.class,
                IntegrationTestCommand.class,
                CoverageCommand.class,
                PackageCommand.class,
                PublishCommand.class,
                sh.zolt.cli.command.supplychain.SbomCommand.class,
                sh.zolt.cli.command.supplychain.LicensesCommand.class,
                RunPackageCommand.class,
                NativeCommand.class,
                NativeSmokeCommand.class,
                ReleaseArchiveCommand.class,
                sh.zolt.cli.command.publish.ReleaseIndexCommand.class,
                ReleaseVerifyCommand.class,
                SelfCheckCommand.class,
                SelfParityCommand.class,
                CleanCommand.class,
                DoctorCommand.class
        })
public final class ZoltCli implements Runnable {
    public static final String VERSION = "0.1.0-SNAPSHOT";

    /**
     * Effective {@code zolt --version} string: the validated {@code ZOLT_VERSION_OVERRIDE} when set,
     * otherwise the compiled-in {@link #VERSION}. A nightly build exports the computed nightly string
     * into the override so the binary's {@code --version} output agrees with the stamped archives.
     */
    public static String version() {
        String buildVersion = ZoltBuildVersion.version();
        if (!VERSION.equals(buildVersion)) {
            return buildVersion;
        }
        String implementationVersion = ZoltCli.class.getPackage().getImplementationVersion();
        if (implementationVersion != null && !implementationVersion.isBlank()) {
            return implementationVersion;
        }
        return buildVersion;
    }

    /**
     * Resolves the version at runtime so {@code @Command(versionProvider = ...)} honors the override.
     * picocli annotation values must be compile-time constants, so the version cannot be a dynamic
     * field; reading it through this provider keeps the override load-bearing for the native smoke's
     * {@code --version} equality check.
     */
    public static final class ZoltVersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {version()};
        }
    }

    @Option(
            names = "--color",
            scope = ScopeType.INHERIT,
            paramLabel = "<WHEN>",
            converter = ColorModeConverter.class,
            description = "Control color in human output: auto, always, or never.")
    private ColorMode colorMode = ColorMode.AUTO;

    @Option(
            names = "--progress",
            scope = ScopeType.INHERIT,
            paramLabel = "<WHEN>",
            converter = ProgressModeConverter.class,
            description = "Control progress in human output: auto, always, or never.")
    private ProgressMode progressMode = ProgressMode.AUTO;

    @Option(
            names = "--no-progress",
            scope = ScopeType.INHERIT,
            description = "Disable progress output.")
    private boolean noProgress;

    @Option(
            names = {"-q", "--quiet"},
            scope = ScopeType.INHERIT,
            description = "Suppress Zolt human summaries and auto progress.")
    private boolean quiet;

    @Option(
            names = {"-v", "--verbose"},
            scope = ScopeType.INHERIT,
            description = "Print opt-in diagnostic detail for human output.")
    private boolean verbose;

    @Mixin
    private ZoltUpdateNoticeHook updateNoticeHook = new ZoltUpdateNoticeHook();

    @Mixin
    private ZoltToolchainNoticeHook toolchainNoticeHook = new ZoltToolchainNoticeHook();

    @Option(names = "--list", description = "List available commands.")
    private boolean listCommands;

    @Spec
    private CommandLine.Model.CommandSpec spec;

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String... args) {
        CommandLine commandLine;
        try {
            commandLine = newCommandLine();
        } catch (RuntimeException exception) {
            return reportStartupFailure(exception);
        }
        return commandLine.execute(args);
    }

    /**
     * Renders a machine-configuration failure raised while the command tree is being built. A bad
     * {@code ZOLT_USER_HOME} is read by option defaults before any command runs, so it escapes the
     * parsed-command exception handler; anything without an actionable carrier keeps its existing
     * unhandled-error path.
     */
    private static int reportStartupFailure(RuntimeException exception) {
        CommandLine bare = new CommandLine(CommandLine.Model.CommandSpec.create().name("zolt"));
        if (!sh.zolt.cli.command.CommandFailures.printActionable(bare.getCommandSpec(), exception)) {
            throw exception;
        }
        bare.getErr().flush();
        return bare.getCommandSpec().exitCodeOnExecutionException();
    }

    static CommandLine newCommandLine() {
        ZoltCli rootCommand = new ZoltCli();
        CommandLine commandLine = new CommandLine(rootCommand)
                .setCaseInsensitiveEnumValuesAllowed(true);
        configureUniversalHelp(commandLine);
        CliUsageConfiguration.apply(commandLine, rootCommand::consoleStyle);
        configureExecutionHandling(commandLine, rootCommand);
        commandLine.setParameterExceptionHandler(new sh.zolt.cli.command.task.CommandAliasExpansionHandler());
        return commandLine;
    }

    private static void configureUniversalHelp(CommandLine commandLine) {
        commandLine.getCommandSpec().mixinStandardHelpOptions(true);
        commandLine.getSubcommands().values().forEach(ZoltCli::configureUniversalHelp);
    }

    private static void configureExecutionHandling(CommandLine commandLine, ZoltCli rootCommand) {
        commandLine.setExecutionStrategy(parseResult -> {
            int exitCode = new CommandLine.RunLast().execute(parseResult);
            if (exitCode == 0) {
                rootCommand.updateNoticeHook.printAfterSuccess(commandLine, parseResult, rootCommand.quiet);
                rootCommand.toolchainNoticeHook.printAfterSuccess(commandLine, parseResult, rootCommand.quiet);
            }
            return exitCode;
        });
        commandLine.setExecutionExceptionHandler(ZoltCli::handleExecutionException);
        commandLine.getSubcommands().values().forEach(ZoltCli::configureExecutionHandling);
    }

    private static void configureExecutionHandling(CommandLine commandLine) {
        commandLine.setExecutionExceptionHandler(ZoltCli::handleExecutionException);
        commandLine.getSubcommands().values().forEach(ZoltCli::configureExecutionHandling);
    }

    private static int handleExecutionException(
            Exception exception,
            CommandLine parsedCommandLine,
            CommandLine.ParseResult parseResult) {
        if (!PrintedUserException.alreadyPrinted(exception)) {
            if (!sh.zolt.cli.command.CommandFailures.printActionable(
                    parsedCommandLine.getCommandSpec(), exception)) {
                CommandHumanOutput output = CommandHumanOutput.errors(parsedCommandLine.getCommandSpec());
                output.error(exception.getMessage());
                parsedCommandLine.getErr().flush();
            }
        }
        return parsedCommandLine.getCommandSpec().exitCodeOnExecutionException();
    }

    @Override
    public void run() {
        if (listCommands) {
            spec.commandLine().getOut().println(consoleStyle().helpHeading("Commands:"));
            spec.commandLine().getOut().print(RootCommandListRenderer.render(spec.commandLine(), consoleStyle()));
            spec.commandLine().getOut().flush();
            return;
        }
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    ConsoleStyle consoleStyle() {
        boolean interactive = System.console() != null;
        return ConsoleStyle.of(colorMode, interactive, System.getenv());
    }

    ProgressPolicy progressPolicy() {
        boolean interactiveStderr = System.console() != null;
        boolean suppressProgress = noProgress || (quiet && progressMode != ProgressMode.ALWAYS);
        return ProgressPolicy.of(progressMode, suppressProgress, interactiveStderr, System.getenv());
    }

    boolean quiet() {
        return quiet;
    }

    boolean verbose() {
        return verbose;
    }

    public static final class TimingOptions {
        @Option(names = "--timings", description = "Print command timing information.")
        private boolean enabled;

        @Option(names = "--timings-format", description = "Timing output format: text or json.")
        private TimingFormat format = TimingFormat.TEXT;

        public boolean enabled() {
            return enabled;
        }

        public TimingFormat format() {
            return format;
        }
    }

}
