package sh.zolt.cli.command.insight;

import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.error.ActionableError;
import sh.zolt.error.ActionableException;
import sh.zolt.lockfile.LockDependencyGraphException;
import sh.zolt.lockfile.WorkspaceGraphLockCapability;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.tree.DependencyJsonFormatter;
import sh.zolt.tree.DependencyTreeFormatter;
import sh.zolt.tree.WorkspaceDependencyJsonFormatter;
import sh.zolt.tree.WorkspaceDependencyTreeFormatter;
import sh.zolt.tree.WorkspaceTreeMember;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.resolve.WorkspaceMemberGraphRoots;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "tree", description = "Display the resolved dependency graph.")
public final class TreeCommand implements Runnable {
    private final ManifestProjectLoader projectLoader;
    private final ZoltLockfileReader lockfileReader;
    private final DependencyJsonFormatter jsonFormatter;
    private final DependencyTreeFormatter treeFormatter;
    private final ManifestWorkspaceLoader workspaceDiscovery = new ManifestWorkspaceLoader();
    private final WorkspaceDependencyJsonFormatter workspaceJsonFormatter =
            new WorkspaceDependencyJsonFormatter();
    private final WorkspaceDependencyTreeFormatter workspaceTreeFormatter =
            new WorkspaceDependencyTreeFormatter();
    private final WorkspaceMemberGraphRoots memberGraphRoots = new WorkspaceMemberGraphRoots();

    enum Format {
        TEXT,
        JSON
    }

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--workspace", description = "Project the discovered workspace from the root zolt.lock.")
    private boolean workspace;

    @Option(names = "--format", description = "Output format: text or json.")
    private Format format = Format.TEXT;

    @Spec
    private CommandSpec spec;

    public TreeCommand() {
        this(
                new ManifestProjectLoader(),
                new ZoltLockfileReader(),
                new DependencyJsonFormatter(),
                new DependencyTreeFormatter());
    }

    TreeCommand(
            ManifestProjectLoader projectLoader,
            ZoltLockfileReader lockfileReader,
            DependencyJsonFormatter jsonFormatter,
            DependencyTreeFormatter treeFormatter) {
        this.projectLoader = projectLoader;
        this.lockfileReader = lockfileReader;
        this.jsonFormatter = jsonFormatter;
        this.treeFormatter = treeFormatter;
    }

    @Override
    public void run() {
        try {
            CommandOutput.printAndFlush(spec, workspace ? formatWorkspace() : formatProject());
        } catch (LockfileReadException
                | ZoltConfigException
                | ActionableException
                | WorkspaceConfigException
                | LockDependencyGraphException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private String formatProject() {
        Path projectRoot = projectDirectory.path();
        ProjectConfig config = projectLoader.load(projectRoot);
        ZoltLockfile lockfile = lockfileReader.read(projectRoot.resolve("zolt.lock"));
        return format == Format.JSON
                ? jsonFormatter.tree(config, lockfile)
                : treeFormatter.format(config, lockfile);
    }

    /**
     * A pure projection of the committed root lock: discovery reads the workspace config, and the graph
     * is re-read from {@code zolt.lock}. Nothing here resolves, downloads, or writes.
     */
    private String formatWorkspace() {
        Workspace discovered = workspaceDiscovery.discover(projectDirectory.path())
                .orElseThrow(() -> new ActionableException(ActionableError.of(
                        "No Zolt workspace was found for `zolt tree --workspace`.",
                        "Run from a workspace root, or drop --workspace to print a single-project tree.")));
        Path lockfilePath = discovered.root().resolve("zolt.lock");
        if (!Files.isRegularFile(lockfilePath)) {
            throw new ActionableException(ActionableError.of(
                    "No zolt.lock found at " + lockfilePath + ".",
                    "Run `zolt resolve --workspace` to generate it, then re-run `zolt tree --workspace`."));
        }
        ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
        WorkspaceGraphLockCapability.requireMemberGraphEvidence(lockfile);
        String name = discovered.config().name();
        List<String> memberPaths = discovered.members().stream()
                .map(WorkspaceMember::path)
                .toList();
        return format == Format.JSON
                ? workspaceJsonFormatter.tree(
                        name,
                        discovered.members().stream()
                                .map(member -> WorkspaceTreeMember.from(
                                        member.path(),
                                        member.config(),
                                        memberGraphRoots.roots(
                                                member.path(), member.config(), lockfile, discovered)))
                                .toList(),
                        lockfile)
                : workspaceTreeFormatter.format(name, memberPaths, lockfile);
    }
}
