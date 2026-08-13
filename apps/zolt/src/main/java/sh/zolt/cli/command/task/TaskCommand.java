package sh.zolt.cli.command.task;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.command.CommandTask;
import sh.zolt.process.ProcessInputPolicy;
import sh.zolt.process.ProcessSupervisor;
import sh.zolt.process.SupervisedProcessResult;
import sh.zolt.process.SupervisedProcessSpec;
import sh.zolt.toml.ZoltConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "task", description = "Run one configured project task.")
public final class TaskCommand implements Callable<Integer> {
    private final TaskCommandConfigLoader configLoader;

    @Parameters(index = "0", paramLabel = "<NAME>", description = "Task name.")
    private String name;

    @Parameters(index = "1..*", arity = "0..*", paramLabel = "ARGS", description = "Arguments appended after --.")
    private List<String> passthrough = List.of();

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Spec
    private CommandSpec spec;

    public TaskCommand() {
        this(new TaskCommandConfigLoader());
    }

    TaskCommand(TaskCommandConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    @Override
    public Integer call() {
        try {
            LoadedCommandConfig loaded = configLoader.load(projectDirectory, spec);
            CommandTask task = Optional.ofNullable(loaded.config().tasks().get(name))
                    .orElseThrow(() -> unknownTask(loaded));
            return runTask(loaded, task);
        } catch (ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private int runTask(LoadedCommandConfig loaded, CommandTask task) {
        Path cwd = resolveCwd(loaded, task);
        List<String> command = new ArrayList<>(task.cmd());
        command.addAll(passthrough);

        java.util.Map<String, String> environment = new java.util.HashMap<>(task.env());
        environment.put("ZOLT_TASK_NAME", task.name());
        environment.put("ZOLT_PROJECT_ROOT", loaded.root().toString());
        SupervisedProcessResult result;
        try {
            result = new ProcessSupervisor().run(
                    SupervisedProcessSpec.builder(command)
                            .directory(cwd)
                            .environment(environment)
                            .mergeErrorStream(false)
                            .inputPolicy(ProcessInputPolicy.INHERIT)
                            .stdoutConsumer(chunk -> write(spec.commandLine().getOut(), chunk))
                            .stderrConsumer(chunk -> write(spec.commandLine().getErr(), chunk))
                            .build());
        } catch (IOException exception) {
            throw CommandFailures.user(
                    spec,
                    "Could not start task `"
                            + task.name()
                            + "` executable `"
                            + task.cmd().getFirst()
                            + "` in "
                            + cwd
                            + ". Check that the executable exists and is runnable.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw CommandFailures.user(spec, "Task `" + task.name() + "` was interrupted.", exception);
        }
        int exitCode = result.exitCode();
        if (exitCode != 0) {
            CommandHumanOutput.errors(spec).error("Task `" + task.name() + "` exited with code " + exitCode + ".");
        }
        return exitCode;
    }

    private Path resolveCwd(LoadedCommandConfig loaded, CommandTask task) {
        Path root = loaded.root().toAbsolutePath().normalize();
        Path cwd = task.cwd().map(root::resolve).orElse(root).normalize();
        if (!cwd.startsWith(root)) {
            throw new ZoltConfigException(
                    "Invalid cwd for task `"
                            + task.name()
                            + "`: "
                            + cwd
                            + ". Task working directories must stay under "
                            + root
                            + ".");
        }
        if (!Files.isDirectory(cwd)) {
            throw new ZoltConfigException(
                    "Invalid cwd for task `"
                            + task.name()
                            + "`: "
                            + cwd
                            + ". Create the directory or update [commands.tasks."
                            + task.name()
                            + "].cwd.");
        }
        try {
            Path realRoot = root.toRealPath();
            Path realCwd = cwd.toRealPath();
            if (!realCwd.startsWith(realRoot)) {
                throw new ZoltConfigException(
                        "Invalid cwd for task `"
                                + task.name()
                                + "`: "
                                + cwd
                                + ". Task working directories must stay under "
                                + root
                                + ".");
            }
            return realCwd;
        } catch (IOException exception) {
            throw new ZoltConfigException(
                    "Invalid cwd for task `"
                            + task.name()
                            + "`: "
                            + cwd
                            + ". Check that the directory exists and is readable.");
        }
    }

    private ZoltConfigException unknownTask(LoadedCommandConfig loaded) {
        String available = loaded.config().tasks().isEmpty()
                ? "No tasks are configured."
                : "Available tasks: " + String.join(", ", loaded.config().tasks().keySet()) + ".";
        return new ZoltConfigException(
                "Unknown task `"
                        + name
                        + "` in "
                        + loaded.configPath()
                        + ". "
                        + available
                        + " Run `zolt tasks` to list configured tasks.");
    }

    private static void write(java.io.PrintWriter writer, String chunk) {
        writer.write(chunk);
        writer.flush();
    }
}
