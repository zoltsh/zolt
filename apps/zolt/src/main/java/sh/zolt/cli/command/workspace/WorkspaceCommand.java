package sh.zolt.cli.command.workspace;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.discovery.DiscoveredWorkspace;
import sh.zolt.workspace.discovery.ManifestWorkspaceDiscovery;

@Command(
        name = "workspace",
        description = "Inspect the final manifest workspace.",
        subcommands = WorkspaceCommand.MembersCommand.class)
public final class WorkspaceCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "members", description = "List final workspace membership and selection evidence.")
    public static final class MembersCommand implements Runnable {
        private final ManifestWorkspaceDiscovery discovery;
        private final WorkspaceMembersFormatter formatter;

        enum Format {
            TEXT,
            JSON
        }

        @Mixin
        private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

        @Option(names = "--format", description = "Output format: text or json.")
        private Format format = Format.TEXT;

        @Option(names = "--schema-version", description = "JSON schema version: 1.")
        private String schemaVersion = "1";

        @Spec
        private CommandSpec spec;

        public MembersCommand() {
            this(new ManifestWorkspaceDiscovery(), new WorkspaceMembersFormatter());
        }

        MembersCommand(
                ManifestWorkspaceDiscovery discovery,
                WorkspaceMembersFormatter formatter) {
            this.discovery = discovery;
            this.formatter = formatter;
        }

        @Override
        public void run() {
            try {
                validateSchemaSelection();
                DiscoveredWorkspace workspace = discovery.discover(projectDirectory.path())
                        .orElseThrow(() -> new WorkspaceConfigException(
                                "No final Zolt workspace was found for `zolt workspace members`."));
                CommandOutput.printAndFlush(
                        spec,
                        format == Format.JSON ? formatter.json(workspace) : formatter.text(workspace));
            } catch (WorkspaceConfigException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }

        private void validateSchemaSelection() {
            boolean selected = spec.commandLine().getParseResult().hasMatchedOption("--schema-version");
            if (selected && format != Format.JSON) {
                throw new WorkspaceConfigException(
                        "--schema-version is available only with --format json.");
            }
            if (!schemaVersion.equals("1")) {
                throw new WorkspaceConfigException(
                        "Unsupported workspace-members JSON schema version `"
                                + schemaVersion + "`. Use 1.");
            }
        }
    }
}
