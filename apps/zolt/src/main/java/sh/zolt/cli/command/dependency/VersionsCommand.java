package sh.zolt.cli.command.dependency;

import sh.zolt.cache.ArtifactCacheException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandFrameworkServices;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandResolveOutput;
import sh.zolt.cli.command.CommandServiceBundles.CommandVersionAliasServices;
import sh.zolt.cli.command.dependency.VersionAliasCommands.VersionAliasCommandException;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.mutation.AuthoredManifestMutator;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Owns the {@code [versions]} alias map: {@code set} upserts, {@code remove} deletes (design §20). */
@Command(
        name = "versions",
        description = "Manage version aliases in [versions].",
        subcommands = {
                VersionsCommand.SetCommand.class,
                VersionsCommand.RemoveCommand.class
        })
public final class VersionsCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "set", description = "Set a version alias in zolt.toml and refresh zolt.lock.")
    public static final class SetCommand implements Runnable {
        private final ManifestMutationServices manifests;
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
        private Path cacheRoot = LocalArtifactCache.defaultRoot();

        @Spec
        private CommandSpec spec;

        public SetCommand() {
            this(CommandFrameworkServices.versionAliasCommandServices());
        }

        private SetCommand(CommandVersionAliasServices services) {
            this(services.manifests(), services.resolveService());
        }

        SetCommand(ManifestMutationServices manifests, ResolveService resolveService) {
            this.manifests = manifests;
            this.resolveService = resolveService;
        }

        @Override
        public void run() {
            try {
                LocalId normalizedAlias = VersionAliasCommands.validateAlias(alias);
                String normalizedVersion = VersionAliasCommands.validateValue(normalizedAlias, version);
                ManifestEditResult edit = ManifestEditTransaction.execute(
                        projectDirectory.path(),
                        cacheRoot,
                        noResolve,
                        manifests,
                        resolveService,
                        current -> AuthoredManifestMutator.setVersionAlias(
                                current, normalizedAlias, new VersionAliasValue(normalizedVersion)));
                CommandHumanOutput output = CommandHumanOutput.of(spec);
                VersionAliasValue previous = DependencyEditCommands.versionAliases(edit.original())
                        .get(normalizedAlias);
                if (previous == null) {
                    output.success("Added version alias " + normalizedAlias + " = " + normalizedVersion
                            + " to [versions]");
                } else if (previous.value().equals(normalizedVersion)) {
                    output.detail("Version alias " + normalizedAlias + " already equals "
                            + normalizedVersion + " in [versions]");
                } else {
                    output.success("Updated version alias " + normalizedAlias + " from " + previous
                            + " to " + normalizedVersion + " in [versions]");
                }
                if (noResolve) {
                    output.detail("Skipped resolve; run zolt resolve to refresh zolt.lock.");
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
    }

    @Command(
            name = "remove",
            description = "Remove an unused version alias from zolt.toml and refresh zolt.lock.")
    public static final class RemoveCommand implements Runnable {
        private final ManifestMutationServices manifests;
        private final ResolveService resolveService;

        @Parameters(index = "0", paramLabel = "ALIAS", description = "Version alias name.")
        private String alias;

        @Option(names = "--no-resolve", description = "Update zolt.toml without refreshing zolt.lock.")
        private boolean noResolve;

        @Mixin
        private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

        @Option(names = "--cache-root", hidden = true)
        private Path cacheRoot = LocalArtifactCache.defaultRoot();

        @Spec
        private CommandSpec spec;

        public RemoveCommand() {
            this(CommandFrameworkServices.versionAliasCommandServices());
        }

        private RemoveCommand(CommandVersionAliasServices services) {
            this(services.manifests(), services.resolveService());
        }

        RemoveCommand(ManifestMutationServices manifests, ResolveService resolveService) {
            this.manifests = manifests;
            this.resolveService = resolveService;
        }

        @Override
        public void run() {
            try {
                LocalId normalizedAlias = VersionAliasCommands.validateAlias(alias);
                ManifestEditResult edit = ManifestEditTransaction.execute(
                        projectDirectory.path(),
                        cacheRoot,
                        noResolve,
                        manifests,
                        resolveService,
                        current -> {
                            if (!DependencyEditCommands.versionAliases(current)
                                    .containsKey(normalizedAlias)) {
                                throw new VersionAliasCommandException(
                                        "Version alias `" + normalizedAlias
                                                + "` is not declared in [versions].");
                            }
                            List<String> references =
                                    VersionAliasCommands.references(current, normalizedAlias);
                            if (!references.isEmpty()) {
                                throw new VersionAliasCommandException(
                                        "Version alias `" + normalizedAlias + "` is still referenced by "
                                                + String.join(", ", references)
                                                + ". Remove or update those versionRef declarations before removing [versions]."
                                                + normalizedAlias + ".");
                            }
                            return AuthoredManifestMutator.removeVersionAlias(current, normalizedAlias);
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
