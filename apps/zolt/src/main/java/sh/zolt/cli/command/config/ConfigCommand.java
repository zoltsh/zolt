package sh.zolt.cli.command.config;

import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.effective.EffectiveManifest;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.discovery.DiscoveredWorkspace;
import sh.zolt.workspace.discovery.ManifestWorkspaceDiscovery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "config",
        description = "Inspect manifest configuration.",
        subcommands = {ConfigCommand.ShowCommand.class})
public final class ConfigCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /**
     * Reports one manifest view of the project discovered from the command directory (design §20.2).
     *
     * <p>Exactly one of {@code --manifest} and {@code --effective} is required: the bare command
     * fails with usage rather than choosing a hidden default. {@code --manifest} never materializes
     * workspace inheritance; {@code --effective} resolves the complete workspace context and names
     * every value authored, inherited, or built-in. Neither mode reads machine-local user-global
     * configuration.
     */
    @Command(name = "show", description = "Show the authored or effective manifest configuration.")
    public static final class ShowCommand implements Runnable {
        private static final String MANIFEST = "zolt.toml";

        private final ManifestProjectConfigLoader manifestLoader;
        private final ManifestWorkspaceDiscovery discovery;
        private final ConfigShowFormatter formatter = new ConfigShowFormatter();

        @Option(names = "--manifest", description = "Report the authored values of this project's manifest.")
        private boolean authoredView;

        @Option(
                names = "--effective",
                description = "Report the composed configuration with the origin of every value.")
        private boolean effectiveView;

        @Mixin
        private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

        @Spec
        private CommandSpec spec;

        public ShowCommand() {
            this(new ManifestProjectConfigLoader(), new ManifestWorkspaceDiscovery());
        }

        ShowCommand(ManifestProjectConfigLoader manifestLoader, ManifestWorkspaceDiscovery discovery) {
            this.manifestLoader = manifestLoader;
            this.discovery = discovery;
        }

        @Override
        public void run() {
            if (authoredView == effectiveView) {
                throw new CommandLine.ParameterException(
                        spec.commandLine(),
                        "`zolt config show` requires exactly one of `--manifest` and `--effective`."
                                + " `--manifest` reports authored values; `--effective` reports the"
                                + " composed configuration with the origin of every value.");
            }
            Path directory = projectDirectory.path().toAbsolutePath().normalize();
            try {
                CommandOutput.printAndFlush(
                        spec,
                        (authoredView ? authored(directory) : effective(directory)).stripTrailing());
            } catch (WorkspaceConfigException exception) {
                throw CommandFailures.user(spec, new ZoltConfigException(exception.getMessage()));
            } catch (ZoltConfigException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }

        private String authored(Path directory) {
            Path manifest = nearestManifest(directory);
            return formatter.manifest(
                    label(manifest, directory), manifestLoader.document(manifest).authored());
        }

        private String effective(Path directory) {
            Optional<DiscoveredWorkspace> workspace = discovery.discover(directory);
            if (workspace.isPresent()) {
                DiscoveredWorkspace discovered = workspace.orElseThrow();
                Optional<WorkspaceMemberPath> member = member(discovered, directory);
                if (member.isPresent()) {
                    EffectiveManifest composed =
                            discovered.effective().members().get(member.orElseThrow());
                    return formatter.effective(
                            member.orElseThrow().value() + "/" + MANIFEST,
                            composed,
                            Optional.of(discovered.selection().source().value()));
                }
            }
            Path manifest = nearestManifest(directory);
            return formatter.effective(
                    label(manifest, directory), manifestLoader.effective(manifest), Optional.empty());
        }

        /** The deepest member whose directory contains the command directory, when there is one. */
        private static Optional<WorkspaceMemberPath> member(
                DiscoveredWorkspace workspace, Path directory) {
            return workspace.members().entrySet().stream()
                    .filter(entry -> directory.startsWith(entry.getValue().directory()))
                    .max(Comparator.comparingInt(entry -> entry.getValue().directory().getNameCount()))
                    .map(Map.Entry::getKey);
        }

        private static Path nearestManifest(Path directory) {
            Path current = directory;
            while (current != null) {
                Path manifest = current.resolve(MANIFEST);
                if (Files.isRegularFile(manifest)) {
                    return manifest;
                }
                current = current.getParent();
            }
            throw new ZoltConfigException(
                    "Could not find zolt.toml at or above " + directory
                            + ". Run zolt config show from a project or workspace directory.");
        }

        /** A short label: the manifest path relative to the command directory when it is beneath it. */
        private static String label(Path manifest, Path directory) {
            Path parent = manifest.getParent();
            if (parent != null && parent.equals(directory)) {
                return MANIFEST;
            }
            return manifest.toString();
        }
    }
}
