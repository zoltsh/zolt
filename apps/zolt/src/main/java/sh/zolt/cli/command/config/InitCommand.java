package sh.zolt.cli.command.config;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.project.ProjectConfigWriteException;
import sh.zolt.project.init.ProjectInitException;
import sh.zolt.project.init.ProjectInitResult;
import sh.zolt.project.init.ProjectInitializer;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.toml.WorkspaceTomlWriter;
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

    @Option(names = "--no-tests", description = "Create the project without JUnit or test sources.")
    private boolean noTests;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Spec
    private CommandSpec spec;

    public InitCommand() {
        this(projectInitializer());
    }

    InitCommand(ProjectInitializer projectInitializer) {
        this.projectInitializer = projectInitializer;
    }

    private static ProjectInitializer projectInitializer() {
        ZoltTomlWriter writer = new ZoltTomlWriter();
        WorkspaceTomlWriter workspaceWriter = new WorkspaceTomlWriter();
        return new ProjectInitializer((path, config) -> {
            try {
                writer.write(path, config);
            } catch (ZoltConfigException exception) {
                throw new ProjectConfigWriteException(exception.getMessage(), exception);
            }
        }, (path, config) -> {
            try {
                workspaceWriter.write(
                        path,
                        new WorkspaceConfig(
                                config.name(),
                                config.members(),
                                config.defaultMembers(),
                                config.repositories(),
                                config.platforms()));
            } catch (ZoltConfigException exception) {
                throw new ProjectConfigWriteException(exception.getMessage(), exception);
            }
        });
    }

    @Override
    public void run() {
        try {
            ProjectInitResult result = workspace
                    ? projectInitializer.initWorkspace(projectDirectory.path(), name, group, javaVersion, !noTests)
                    : projectInitializer.init(projectDirectory.path(), name, group, javaVersion, !noTests);
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            output.summary("Created Zolt " + (workspace ? "workspace" : "project") + " at " + result.projectDirectory());
            output.pointer("cd", result.projectDirectory().getFileName().toString());
        } catch (ProjectInitException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }
}
