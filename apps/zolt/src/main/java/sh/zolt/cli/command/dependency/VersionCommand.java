package sh.zolt.cli.command.dependency;

import sh.zolt.cache.ArtifactCacheException;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.ZoltCli;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandFrameworkServices;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandResolveOutput;
import sh.zolt.cli.command.CommandServiceBundles.CommandVersionAliasServices;
import sh.zolt.cli.command.dependency.VersionAliasCommands.VersionAliasCommandException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "version",
        description = "Print the Zolt version.",
        subcommands = {
                VersionCommand.SetCommand.class,
                VersionCommand.RemoveCommand.class
        })
public final class VersionCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().getOut().println(ZoltCli.version());
    }

    @Command(
            name = "set",
            description = "Set a version alias in zolt.toml and refresh zolt.lock.")
    public static final class SetCommand implements Runnable {
        private final ZoltTomlParser tomlParser;
        private final ZoltTomlWriter tomlWriter;
        private final ResolveService resolveService;

        @Parameters(index = "0", paramLabel = "ALIAS", description = "Version alias name.")
        private String alias;

        @Parameters(index = "1", paramLabel = "VERSION", description = "Literal version value.")
        private String version;

        @Option(names = "--no-resolve", description = "Update zolt.toml without refreshing zolt.lock.")
        private boolean noResolve;

        @Mixin
        private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

        @Option(names = "--cache-root", hidden = true)
        private Path cacheRoot = sh.zolt.cache.LocalArtifactCache.defaultRoot();

        @Spec
        private CommandSpec spec;

        public SetCommand() {
            this(CommandFrameworkServices.versionAliasCommandServices());
        }

        private SetCommand(CommandVersionAliasServices services) {
            this(
                    services.tomlParser(),
                    services.tomlWriter(),
                    services.resolveService());
        }

        SetCommand(ZoltTomlParser tomlParser, ZoltTomlWriter tomlWriter, ResolveService resolveService) {
            this.tomlParser = tomlParser;
            this.tomlWriter = tomlWriter;
            this.resolveService = resolveService;
        }

        @Override
        public void run() {
            try {
                String normalizedAlias = VersionAliasCommands.validateAlias(alias);
                String normalizedVersion = VersionAliasCommands.validateValue(normalizedAlias, version);
                Path projectRoot = projectDirectory.path();
                ManifestEditTransaction.Result edit = ManifestEditTransaction.execute(
                        projectRoot,
                        cacheRoot,
                        noResolve,
                        tomlParser,
                        tomlWriter,
                        resolveService,
                        current -> {
                            Map<String, String> aliases = new LinkedHashMap<>(current.versionAliases());
                            aliases.put(normalizedAlias, normalizedVersion);
                            return current.withVersionAliases(aliases);
                        });
                String previous = edit.original().versionAliases().get(normalizedAlias);
                printVersionAliasSummary(normalizedAlias, normalizedVersion, previous);
                if (noResolve) {
                    CommandHumanOutput.of(spec).detail("Skipped resolve; run zolt resolve to refresh zolt.lock.");
                    return;
                }
                if (edit.resolveResult() != null) {
                    CommandResolveOutput.print(spec, edit.resolveResult());
                }
            } catch (ArtifactCacheException
                    | ResolveException
                    | VersionAliasCommandException
                    | ZoltConfigException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }

        private void printVersionAliasSummary(String alias, String version, String previous) {
            if (version.equals(previous)) {
                CommandHumanOutput.of(spec).detail(
                        "Version alias " + alias + " already equals " + version + " in [versions]");
            } else if (previous == null) {
                CommandHumanOutput.of(spec).success(
                        "Added version alias " + alias + " = " + version + " to [versions]");
            } else {
                CommandHumanOutput.of(spec).success(
                        "Updated version alias " + alias + " from " + previous + " to " + version + " in [versions]");
            }
        }
    }

    @Command(
            name = "remove",
            description = "Remove an unused version alias from zolt.toml and refresh zolt.lock.")
    public static final class RemoveCommand implements Runnable {
        private final ZoltTomlParser tomlParser;
        private final ZoltTomlWriter tomlWriter;
        private final ResolveService resolveService;

        @Parameters(index = "0", paramLabel = "ALIAS", description = "Version alias name.")
        private String alias;

        @Option(names = "--no-resolve", description = "Update zolt.toml without refreshing zolt.lock.")
        private boolean noResolve;

        @Mixin
        private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

        @Option(names = "--cache-root", hidden = true)
        private Path cacheRoot = sh.zolt.cache.LocalArtifactCache.defaultRoot();

        @Spec
        private CommandSpec spec;

        public RemoveCommand() {
            this(CommandFrameworkServices.versionAliasCommandServices());
        }

        private RemoveCommand(CommandVersionAliasServices services) {
            this(
                    services.tomlParser(),
                    services.tomlWriter(),
                    services.resolveService());
        }

        RemoveCommand(ZoltTomlParser tomlParser, ZoltTomlWriter tomlWriter, ResolveService resolveService) {
            this.tomlParser = tomlParser;
            this.tomlWriter = tomlWriter;
            this.resolveService = resolveService;
        }

        @Override
        public void run() {
            try {
                String normalizedAlias = VersionAliasCommands.validateAlias(alias);
                Path projectRoot = projectDirectory.path();
                ManifestEditTransaction.Result edit = ManifestEditTransaction.execute(
                        projectRoot,
                        cacheRoot,
                        noResolve,
                        tomlParser,
                        tomlWriter,
                        resolveService,
                        current -> {
                            Map<String, String> currentAliases = new LinkedHashMap<>(current.versionAliases());
                            if (!currentAliases.containsKey(normalizedAlias)) {
                                throw new VersionAliasCommandException(
                                        "Version alias `" + normalizedAlias + "` is not declared in [versions].");
                            }
                            List<String> references = VersionAliasCommands.references(current, normalizedAlias);
                            if (!references.isEmpty()) {
                                throw new VersionAliasCommandException(
                                        "Version alias `"
                                                + normalizedAlias
                                                + "` is still referenced by "
                                                + String.join(", ", references)
                                                + ". Remove or update those versionRef declarations before removing [versions]."
                                                + normalizedAlias
                                                + ".");
                            }
                            currentAliases.remove(normalizedAlias);
                            return current.withVersionAliases(currentAliases);
                        });
                CommandHumanOutput output = CommandHumanOutput.of(spec);
                output.success("Removed version alias " + normalizedAlias + " from [versions]");
                if (noResolve) {
                    output.detail("Skipped resolve; run zolt resolve to refresh zolt.lock.");
                    return;
                }
                CommandResolveOutput.print(spec, edit.resolveResult());
            } catch (ArtifactCacheException
                    | ResolveException
                    | VersionAliasCommandException
                    | ZoltConfigException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }
    }
}
