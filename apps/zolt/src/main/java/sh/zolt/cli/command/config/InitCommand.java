package sh.zolt.cli.command.config;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.init.ProjectInitException;
import sh.zolt.init.ProjectInitResult;
import sh.zolt.init.ProjectInitializer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "init", description = "Create a new Zolt project.")
public final class InitCommand implements Runnable {
    private final ProjectInitializer projectInitializer;

    @Parameters(index = "0", paramLabel = "NAME", description = "Project directory to create.")
    private String name;

    @Option(names = "--group", description = "Java package group for generated sources.")
    private String group = "com.example";

    @Option(names = "--java", description = "Java version for zolt.toml.")
    private String javaVersion = "21";

    @Option(names = "--workspace", description = "Create a workspace root with a default app member.")
    private boolean workspace;

    @Option(
            names = "--all-members",
            description = "Omit [workspace.members].default so every member is selected.")
    private boolean allMembers;

    @Option(names = "--no-tests", description = "Create the project without JUnit or test sources.")
    private boolean noTests;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Spec
    private CommandSpec spec;

    public InitCommand() {
        this(new ProjectInitializer());
    }

    InitCommand(ProjectInitializer projectInitializer) {
        this.projectInitializer = projectInitializer;
    }

    @Override
    public void run() {
        if (allMembers && !workspace) {
            throw CommandFailures.user(
                    spec, new ProjectInitException("--all-members requires --workspace."));
        }
        try {
            ProjectInitResult result = workspace
                    ? projectInitializer.initWorkspace(
                            projectDirectory.path(), name, group, javaVersion, !noTests, allMembers)
                    : projectInitializer.init(projectDirectory.path(), name, group, javaVersion, !noTests);
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            output.summary("Created Zolt " + (workspace ? "workspace" : "project") + " at " + result.projectDirectory());
            output.pointer("cd", result.projectDirectory().getFileName().toString());
        } catch (ProjectInitException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }
}
