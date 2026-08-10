package sh.zolt.cli.command.dependency;

import sh.zolt.cache.ArtifactCacheException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandFrameworkServices;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandResolveOutput;
import sh.zolt.cli.command.CommandServiceBundles.CommandDependencyEditServices;
import sh.zolt.cli.command.dependency.DependencyEditCommands.DependencySectionException;
import sh.zolt.cli.command.dependency.DependencyEditCommands.RemoveCommandException;
import sh.zolt.cli.command.dependency.DependencyEditCommands.RemoveRequest;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParseException;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.DependencySection;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import java.nio.file.Path;
import java.util.List;
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
    private final ZoltTomlParser tomlParser;
    private final ZoltTomlWriter tomlWriter;
    private final ResolveService resolveService;

    @Parameters(
            arity = "1..2",
            paramLabel = "DEPENDENCY",
            description = "Dependency coordinate. May be prefixed with api, runtime, provided, dev, test, processor, or test-processor.")
    private List<String> arguments;

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
        this(
                services.coordinateParser(),
                services.tomlParser(),
                services.tomlWriter(),
                services.resolveService());
    }

    RemoveCommand(
            CoordinateParser coordinateParser,
            ZoltTomlParser tomlParser,
            ZoltTomlWriter tomlWriter,
            ResolveService resolveService) {
        this.coordinateParser = coordinateParser;
        this.tomlParser = tomlParser;
        this.tomlWriter = tomlWriter;
        this.resolveService = resolveService;
    }

    @Override
    public void run() {
        try {
            RemoveRequest request = parseRequest(arguments);
            Path projectRoot = projectDirectory.path();
            String section = DependencyEditCommands.sectionName(request.section());
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            ManifestEditResult edit = ManifestEditTransaction.execute(
                    projectRoot,
                    cacheRoot,
                    false,
                    tomlParser,
                    tomlWriter,
                    resolveService,
                    current -> {
                        if (!DependencyEditCommands.hasDependency(
                                current,
                                request.section(),
                                request.coordinate())) {
                            return current;
                        }
                        return tomlWriter.removeDependency(
                                current,
                                request.section(),
                                request.coordinate());
                    });
            if (!edit.changed()) {
                output.detail("Dependency " + request.coordinate()
                        + " is not present in [" + section + "]; nothing to remove.");
                return;
            }
            output.summary("Removed dependency " + request.coordinate() + " from [" + section + "]");
            CommandResolveOutput.print(spec, edit.resolveResult());
        } catch (RemoveCommandException
                | DependencySectionException
                | ArtifactCacheException
                | CoordinateParseException
                | ResolveException
                | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private RemoveRequest parseRequest(List<String> values) {
        DependencySection section = DependencyEditCommands.parseSection(values, "zolt remove");
        String rawCoordinate = values.size() == 2 ? values.get(1) : values.get(0);
        Coordinate coordinate = coordinateParser.parse(rawCoordinate);
        return new RemoveRequest(section, coordinate.groupId() + ":" + coordinate.artifactId());
    }
}
