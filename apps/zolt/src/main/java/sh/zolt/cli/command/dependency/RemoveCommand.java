package sh.zolt.cli.command.dependency;

import sh.zolt.cache.ArtifactCacheException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandFrameworkServices;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandResolveOutput;
import sh.zolt.cli.command.CommandServiceBundles.CommandDependencyEditServices;
import sh.zolt.cli.command.dependency.DependencyEditCommands.DependencyScopeException;
import sh.zolt.cli.command.dependency.DependencyEditCommands.RemoveCommandException;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.authored.mutation.AuthoredManifestMutator;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParseException;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "remove",
        description = "Remove a dependency and prune unused transitive packages.")
public final class RemoveCommand implements Runnable {
    private final CoordinateParser coordinateParser;
    private final ManifestMutationServices manifests;
    private final ResolveService resolveService;

    @Parameters(index = "0", paramLabel = "GROUP:ARTIFACT", description = "Dependency coordinate.")
    private String argument;

    @Option(
            names = "--scope",
            paramLabel = "<SCOPE>",
            description = "Dependency scope: implementation (default), api, runtime, provided, dev, test, processor, or test-processor.")
    private String scope;

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
        this(services.coordinateParser(), services.manifests(), services.resolveService());
    }

    RemoveCommand(
            CoordinateParser coordinateParser,
            ManifestMutationServices manifests,
            ResolveService resolveService) {
        this.coordinateParser = coordinateParser;
        this.manifests = manifests;
        this.resolveService = resolveService;
    }

    @Override
    public void run() {
        try {
            DependencyLane lane = DependencyEditCommands.parseScope(scope, "zolt remove");
            Coordinate parsed = coordinateParser.parse(argument);
            if (parsed.version().isPresent()) {
                throw new RemoveCommandException(
                        "Dependency remove coordinate must not include a version. Use `group:artifact`.");
            }
            DependencyCoordinate coordinate = new DependencyCoordinate(
                    parsed.groupId() + ":" + parsed.artifactId());
            String section = DependencyEditCommands.section(lane);
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            ManifestEditResult edit = ManifestEditTransaction.execute(
                    projectDirectory.path(),
                    cacheRoot,
                    noResolve,
                    manifests,
                    resolveService,
                    current -> AuthoredManifestMutator.removeDependency(current, lane, coordinate));
            if (!edit.changed()) {
                output.detail("Dependency " + coordinate
                        + " is not present in [" + section + "]; nothing to remove.");
                return;
            }
            output.summary("Removed dependency " + coordinate + " from [" + section + "]");
            if (noResolve) {
                output.detail("Skipped resolve; run zolt resolve to refresh zolt.lock.");
                return;
            }
            CommandResolveOutput.print(spec, edit.resolveResult());
        } catch (RemoveCommandException
                | DependencyScopeException
                | ArtifactCacheException
                | CoordinateParseException
                | ResolveException
                | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }
}
