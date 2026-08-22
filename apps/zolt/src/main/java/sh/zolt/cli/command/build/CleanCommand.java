package sh.zolt.cli.command.build;

import sh.zolt.build.CleanException;
import sh.zolt.build.clean.CleanResult;
import sh.zolt.build.clean.CleanService;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandWorkspaceSelections;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.workspace.clean.WorkspaceCleanResult;
import sh.zolt.workspace.clean.WorkspaceCleanService;
import sh.zolt.workspace.WorkspaceConfigException;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "clean", description = "Remove project build output.")
public final class CleanCommand implements Runnable {
    private final ManifestProjectLoader projectLoader;
    private final CleanService cleanService;
    private final WorkspaceCleanService workspaceCleanService;

    @Option(names = "--workspace", description = "Clean workspace members in dependency order.")
    private boolean workspace;

    @Option(names = "--all", description = "Select every workspace member.")
    private boolean all;

    @Option(names = "--member", description = "Select a workspace member by declared path. May be repeated.")
    private List<String> members = List.of();

    @Option(names = "--members", split = ",", description = "Select comma-separated workspace members by declared path.")
    private List<String> memberGroups = List.of();

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Spec
    private CommandSpec spec;

    public CleanCommand() {
        this(new ManifestProjectLoader(), new CleanService(), new WorkspaceCleanService());
    }

    CleanCommand(
            ManifestProjectLoader projectLoader,
            CleanService cleanService,
            WorkspaceCleanService workspaceCleanService) {
        this.projectLoader = projectLoader;
        this.cleanService = cleanService;
        this.workspaceCleanService = workspaceCleanService;
    }

    @Override
    public void run() {
        try {
            Path projectRoot = projectDirectory.path();
            if (workspace) {
                WorkspaceCleanResult result = workspaceCleanService.clean(
                        projectRoot,
                        CommandWorkspaceSelections.from(all, members, memberGroups));
                printWorkspaceResult(result);
                return;
            }
            ProjectConfig config = projectLoader.load(projectRoot);
            CleanResult result = cleanService.clean(projectRoot, config);
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            if (result.deletedPaths().isEmpty()) {
                output.detail("Nothing to clean");
                return;
            }
            output.success("Deleted " + result.deletedCount() + " build output paths");
            for (Path path : result.deletedPaths()) {
                output.success("Deleted " + path);
            }
        } catch (CleanException | WorkspaceConfigException | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private void printWorkspaceResult(WorkspaceCleanResult result) {
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        if (result.deletedCount() == 0) {
            output.detail("Nothing to clean");
            return;
        }
        output.success("Deleted "
                + result.deletedCount()
                + " workspace build output paths across "
                + result.members().size()
                + " members");
        for (WorkspaceCleanResult.MemberCleanResult member : result.members()) {
            for (Path path : member.result().deletedPaths()) {
                output.success("Deleted " + member.member() + " " + path);
            }
        }
    }
}
