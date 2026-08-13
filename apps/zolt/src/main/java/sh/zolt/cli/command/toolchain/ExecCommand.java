package sh.zolt.cli.command.toolchain;

import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.config.UserGlobalConfigException;
import sh.zolt.config.UserGlobalConfigParser;
import sh.zolt.error.ActionableException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectVersionOverride;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.process.ProcessInputPolicy;
import sh.zolt.process.ProcessSupervisor;
import sh.zolt.process.SupervisedProcessResult;
import sh.zolt.process.SupervisedProcessSpec;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toolchain.JavaToolchainEnvironment;
import sh.zolt.toolchain.JavaToolchainExecutionService;
import sh.zolt.toolchain.ToolchainConfigReader;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "exec", description = "Run a command inside the resolved Java toolchain.")
public final class ExecCommand implements java.util.concurrent.Callable<Integer> {
    private final ZoltTomlParser tomlParser;
    private final ToolchainConfigReader toolchainConfigReader;
    private final UserGlobalConfigParser globalConfigParser;
    private final JavaToolchainExecutionService toolchains;
    private final ProcessLauncher processLauncher;

    @Parameters(
            arity = "1..*",
            paramLabel = "COMMAND",
            description = "Command and arguments to run after `--`.")
    private List<String> command = List.of();

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--global", description = "Run with the user global Java toolchain default.")
    private boolean global;

    @Option(names = "--global-config", hidden = true)
    private Path globalConfigPath = GlobalToolchainPaths.defaultConfigPath();

    @Option(names = "--toolchain-target", hidden = true)
    private String toolchainTarget;

    @Option(names = "--toolchain-install-root", hidden = true)
    private Path toolchainInstallRoot;

    @Spec
    private CommandSpec spec;

    public ExecCommand() {
        this(
                new ZoltTomlParser(),
                new ToolchainConfigReader(),
                new UserGlobalConfigParser(),
                new JavaToolchainExecutionService(),
                ExecCommand::runProcess);
    }

    ExecCommand(
            ZoltTomlParser tomlParser,
            ToolchainConfigReader toolchainConfigReader,
            UserGlobalConfigParser globalConfigParser,
            JavaToolchainExecutionService toolchains,
            ProcessLauncher processLauncher) {
        this.tomlParser = tomlParser;
        this.toolchainConfigReader = toolchainConfigReader;
        this.globalConfigParser = globalConfigParser;
        this.toolchains = toolchains;
        this.processLauncher = processLauncher;
    }

    @Override
    public Integer call() {
        Path projectRoot = projectDirectory.path();
        try {
            JavaToolchainEnvironment environment = environment(projectRoot);
            ProcessResult result = processLauncher.run(
                    command,
                    projectRoot,
                    environment,
                    chunk -> write(spec.commandLine().getOut(), chunk),
                    chunk -> write(spec.commandLine().getErr(), chunk));
            return result.exitCode();
        } catch (ActionableException | UserGlobalConfigException | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private JavaToolchainEnvironment environment(Path projectRoot) {
        if (global || !Files.isRegularFile(projectRoot.resolve("zolt.toml"))) {
            JavaToolchainRequest request = GlobalToolchainCommand.globalRequest(
                    globalConfigParser.read(globalConfigPath));
            return toolchains.environment(
                    request,
                    "global default",
                    GlobalToolchainPaths.lockfile(globalConfigPath),
                    HostPlatform.parse(toolchainTarget),
                    new ToolchainStore(toolchainInstallRoot),
                    "Java toolchain is not ready for exec",
                    "Run `zolt toolchain status --global` for details, then `zolt toolchain sync --global`, or choose a project with a usable Java toolchain.");
        }
        JavaToolchainRequest configured = toolchainConfigReader
                .readJava(projectRoot.resolve("zolt.toml"))
                .orElse(null);
        if (configured != null) {
            return toolchains.environment(
                    configured,
                    "[toolchain.java]",
                    projectRoot.resolve("zolt.lock"),
                    HostPlatform.parse(toolchainTarget),
                    new ToolchainStore(toolchainInstallRoot),
                    "Java toolchain is not ready for exec",
                    "Run `zolt toolchain status` for details, then `zolt toolchain sync`, or choose a project with a usable Java toolchain.");
        }
        ProjectConfig config = ProjectVersionOverride.apply(
                tomlParser.parse(projectRoot.resolve("zolt.toml")));
        return toolchains.environment(
                projectRoot,
                config,
                HostPlatform.parse(toolchainTarget),
                new ToolchainStore(toolchainInstallRoot));
    }

    private static ProcessResult runProcess(
            List<String> command,
            Path projectRoot,
            JavaToolchainEnvironment environment,
            Consumer<String> stdoutConsumer,
            Consumer<String> stderrConsumer) {
        try {
            Map<String, String> childEnvironment = new HashMap<>();
            childEnvironment.put("JAVA_HOME", environment.javaHome().toString());
            childEnvironment.put("PATH", pathWithToolchainBin(System.getenv("PATH"), environment.bin()));
            SupervisedProcessResult result = new ProcessSupervisor().run(
                    SupervisedProcessSpec.builder(resolveCommand(command, environment))
                            .directory(projectRoot)
                            .environment(childEnvironment)
                            .mergeErrorStream(false)
                            .inputPolicy(ProcessInputPolicy.INHERIT)
                            .stdoutConsumer(stdoutConsumer)
                            .stderrConsumer(stderrConsumer)
                            .build());
            return new ProcessResult(result.exitCode());
        } catch (IOException exception) {
            throw new ActionableException(
                    "Could not run command through zolt exec.",
                    "Check that `" + command.getFirst() + "` exists in the resolved Java toolchain or provide an executable path.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ActionableException(
                    "zolt exec was interrupted.",
                    "Run the command again when the previous process has stopped.");
        }
    }

    private static String pathWithToolchainBin(String path, Path bin) {
        List<String> entries = new ArrayList<>();
        entries.add(bin.toString());
        if (path != null && !path.isBlank()) {
            entries.add(path);
        }
        return String.join(java.io.File.pathSeparator, entries);
    }

    private static List<String> resolveCommand(List<String> command, JavaToolchainEnvironment environment) {
        List<String> resolved = new ArrayList<>(command);
        String executable = resolved.getFirst();
        if (!hasPathSeparator(executable)) {
            Path candidate = resolvedTool(executable, environment);
            if (candidate != null
                    && java.nio.file.Files.isRegularFile(candidate)
                    && java.nio.file.Files.isExecutable(candidate)) {
                resolved.set(0, candidate.toString());
            }
        }
        return List.copyOf(resolved);
    }

    private static Path resolvedTool(String executable, JavaToolchainEnvironment environment) {
        return switch (executable) {
            case "java" -> environment.resolved().java().orElse(environment.bin().resolve(executable).normalize());
            case "javac" -> environment.resolved().javac().orElse(environment.bin().resolve(executable).normalize());
            case "jar" -> environment.resolved().jar().orElse(environment.bin().resolve(executable).normalize());
            case "native-image" -> environment.resolved().nativeImage().orElse(environment.bin().resolve(executable).normalize());
            default -> environment.bin().resolve(executable).normalize();
        };
    }

    private static boolean hasPathSeparator(String value) {
        return value.contains("/") || value.contains("\\");
    }

    private static void write(java.io.PrintWriter writer, String chunk) {
        writer.write(chunk);
        writer.flush();
    }

    @FunctionalInterface
    interface ProcessLauncher {
        ProcessResult run(
                List<String> command,
                Path projectRoot,
                JavaToolchainEnvironment environment,
                Consumer<String> stdoutConsumer,
                Consumer<String> stderrConsumer);
    }

    record ProcessResult(int exitCode) {
    }
}
