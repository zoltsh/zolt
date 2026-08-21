package sh.zolt.cli.command.insight;

import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParseException;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.tree.DependencyJsonFormatter;
import sh.zolt.tree.DependencyWhyException;
import sh.zolt.tree.DependencyWhyFormatter;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "why", description = "Explain why a package is present.")
public final class WhyCommand implements Runnable {
    private final CoordinateParser coordinateParser;
    private final ManifestProjectLoader projectLoader;
    private final ZoltLockfileReader lockfileReader;
    private final DependencyJsonFormatter jsonFormatter;
    private final DependencyWhyFormatter whyFormatter;

    enum Format {
        TEXT,
        JSON
    }

    @Parameters(index = "0", paramLabel = "GROUP:ARTIFACT", description = "Package id to explain.")
    private String packageId;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--format", description = "Output format: text or json.")
    private Format format = Format.TEXT;

    @Spec
    private CommandSpec spec;

    public WhyCommand() {
        this(
                new CoordinateParser(),
                new ManifestProjectLoader(),
                new ZoltLockfileReader(),
                new DependencyJsonFormatter(),
                new DependencyWhyFormatter());
    }

    WhyCommand(
            CoordinateParser coordinateParser,
            ManifestProjectLoader projectLoader,
            ZoltLockfileReader lockfileReader,
            DependencyJsonFormatter jsonFormatter,
            DependencyWhyFormatter whyFormatter) {
        this.coordinateParser = coordinateParser;
        this.projectLoader = projectLoader;
        this.lockfileReader = lockfileReader;
        this.jsonFormatter = jsonFormatter;
        this.whyFormatter = whyFormatter;
    }

    @Override
    public void run() {
        try {
            Path projectRoot = projectDirectory.path();
            Coordinate coordinate = coordinateParser.parse(packageId);
            ProjectConfig config = projectLoader.load(projectRoot);
            ZoltLockfile lockfile = lockfileReader.read(projectRoot.resolve("zolt.lock"));
            PackageId target = new PackageId(coordinate.groupId(), coordinate.artifactId());
            String output = format == Format.JSON
                    ? jsonFormatter.why(config, lockfile, target)
                    : whyFormatter.format(config, lockfile, target);
            CommandOutput.printAndFlush(spec, output);
        } catch (CoordinateParseException | DependencyWhyException | LockfileReadException | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }
}
