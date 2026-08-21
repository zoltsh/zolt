package sh.zolt.cli.command.dependency;

import sh.zolt.cache.ArtifactCacheException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandFrameworkServices;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandResolveOutput;
import sh.zolt.cli.command.CommandServiceBundles.CommandDependencyEditServices;
import sh.zolt.cli.command.dependency.DependencyEditCommands.PlatformCommandException;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.mutation.AuthoredManifestMutator;
import sh.zolt.maven.CoordinateParseException;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import java.nio.file.Path;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Owns the {@code [platforms]} map; there is no singular {@code zolt platform} alias (design §20). */
@Command(
        name = "platforms",
        description = "Manage imported platforms in [platforms].",
        subcommands = {
                PlatformsCommand.SetCommand.class,
                PlatformsCommand.RemoveCommand.class
        })
public final class PlatformsCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "set", description = "Set a platform in zolt.toml and refresh zolt.lock.")
    public static final class SetCommand implements Runnable {
        private final ManifestMutationServices manifests;
        private final ResolveService resolveService;

        @Parameters(index = "0", paramLabel = "GROUP:ARTIFACT", description = "Platform BOM coordinate.")
        private String coordinate;

        @Parameters(index = "1", arity = "0..1", paramLabel = "VERSION", description = "Literal platform version.")
        private String version;

        @Option(names = "--version-ref", description = "Use a version alias declared in [versions].")
        private String versionRef;

        @Option(names = "--no-resolve", description = "Update zolt.toml without refreshing zolt.lock.")
        private boolean noResolve;

        @Mixin
        private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

        @Option(names = "--cache-root", hidden = true)
        private Path cacheRoot = LocalArtifactCache.defaultRoot();

        @Spec
        private CommandSpec spec;

        public SetCommand() {
            this(CommandFrameworkServices.dependencyEditCommandServices());
        }

        private SetCommand(CommandDependencyEditServices services) {
            this(services.manifests(), services.resolveService());
        }

        SetCommand(ManifestMutationServices manifests, ResolveService resolveService) {
            this.manifests = manifests;
            this.resolveService = resolveService;
        }

        @Override
        public void run() {
            try {
                DependencyCoordinate platform = DependencyEditCommands.coordinate(
                        coordinate, PlatformCommandException::new);
                ManifestEditResult edit = ManifestEditTransaction.execute(
                        projectDirectory.path(),
                        cacheRoot,
                        noResolve,
                        manifests,
                        resolveService,
                        current -> AuthoredManifestMutator.setPlatform(
                                current, platform, selector(current)));
                CommandHumanOutput output = CommandHumanOutput.of(spec);
                printSummary(output, edit.original(), platform);
                if (noResolve) {
                    output.detail("Skipped resolve; run zolt resolve to refresh zolt.lock.");
                    return;
                }
                if (edit.resolveResult() != null) {
                    CommandResolveOutput.print(spec, edit.resolveResult());
                }
            } catch (PlatformCommandException
                    | ArtifactCacheException
                    | CoordinateParseException
                    | ResolveException
                    | ZoltConfigException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }

        private PlatformSelector selector(AuthoredManifest manifest) {
            return PlatformSelectors.parse(
                    manifest, "platform", version, versionRef, PlatformCommandException::new);
        }

        private void printSummary(
                CommandHumanOutput output, AuthoredManifest original, DependencyCoordinate platform) {
            PlatformSelector selector = selector(original);
            String requested = DependencyEditCommands.describe(original, selector);
            Map<DependencyCoordinate, PlatformSelector> entries = original.platforms()
                    .map(AuthoredPlatforms::entries)
                    .orElseGet(Map::of);
            PlatformSelector existing = entries.get(platform);
            if (existing == null) {
                output.summary(selector instanceof PlatformSelector.FixedVersion fixed
                        ? "Added platform " + platform + ":" + fixed.value() + " to [platforms]"
                        : "Added platform " + platform + " with " + requested + " to [platforms]");
                return;
            }
            String current = DependencyEditCommands.describe(original, existing);
            if (current.equals(requested)) {
                output.detail(selector instanceof PlatformSelector.FixedVersion fixed
                        ? "Platform " + platform + ":" + fixed.value() + " already exists in [platforms]"
                        : "Platform " + platform + " already uses " + requested + " in [platforms]");
            } else {
                output.summary("Updated platform " + platform + " from " + current + " to " + requested
                        + " in [platforms]");
            }
        }
    }

    @Command(name = "remove", description = "Remove a platform and refresh zolt.lock.")
    public static final class RemoveCommand implements Runnable {
        private final ManifestMutationServices manifests;
        private final ResolveService resolveService;

        @Parameters(index = "0", paramLabel = "GROUP:ARTIFACT", description = "Platform BOM coordinate.")
        private String coordinate;

        @Option(names = "--no-resolve", description = "Update zolt.toml without refreshing zolt.lock.")
        private boolean noResolve;

        @Mixin
        private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

        @Option(names = "--cache-root", hidden = true)
        private Path cacheRoot = LocalArtifactCache.defaultRoot();

        @Spec
        private CommandSpec spec;

        public RemoveCommand() {
            this(CommandFrameworkServices.dependencyEditCommandServices());
        }

        private RemoveCommand(CommandDependencyEditServices services) {
            this(services.manifests(), services.resolveService());
        }

        RemoveCommand(ManifestMutationServices manifests, ResolveService resolveService) {
            this.manifests = manifests;
            this.resolveService = resolveService;
        }

        @Override
        public void run() {
            try {
                DependencyCoordinate platform = DependencyEditCommands.coordinate(
                        coordinate, PlatformCommandException::new);
                CommandHumanOutput output = CommandHumanOutput.of(spec);
                ManifestEditResult edit = ManifestEditTransaction.execute(
                        projectDirectory.path(),
                        cacheRoot,
                        noResolve,
                        manifests,
                        resolveService,
                        current -> AuthoredManifestMutator.removePlatform(current, platform));
                if (!edit.changed()) {
                    output.detail("Platform " + platform + " is not present in [platforms]; nothing to remove.");
                    return;
                }
                output.summary("Removed platform " + platform + " from [platforms]");
                if (noResolve) {
                    output.detail("Skipped resolve; run zolt resolve to refresh zolt.lock.");
                    return;
                }
                CommandResolveOutput.print(spec, edit.resolveResult());
            } catch (PlatformCommandException
                    | ArtifactCacheException
                    | CoordinateParseException
                    | ResolveException
                    | ZoltConfigException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }
    }
}
