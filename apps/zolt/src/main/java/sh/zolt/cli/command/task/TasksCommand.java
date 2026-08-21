package sh.zolt.cli.command.task;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.command.CommandTask;
import sh.zolt.toml.ZoltConfigException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "tasks", description = "List configured project tasks.")
public final class TasksCommand implements Runnable {
    private final TaskCommandConfigLoader configLoader;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Spec
    private CommandSpec spec;

    public TasksCommand() {
        this(new TaskCommandConfigLoader());
    }

    TasksCommand(TaskCommandConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    @Override
    public void run() {
        try {
            LoadedCommandConfig loaded = configLoader.load(projectDirectory);
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            if (loaded.config().tasks().isEmpty()) {
                output.detail("No tasks configured");
                output.next("Add [tasks.<name>] to " + loaded.configPath());
                return;
            }

            output.line("Tasks:");
            for (CommandTask task : loaded.config().tasks().values()) {
                String description = task.description().orElse("");
                output.line("  " + padded(task.name()) + description);
            }
        } catch (ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private static String padded(String value) {
        int width = 20;
        if (value.length() >= width) {
            return value + "  ";
        }
        return value + " ".repeat(width - value.length());
    }
}
