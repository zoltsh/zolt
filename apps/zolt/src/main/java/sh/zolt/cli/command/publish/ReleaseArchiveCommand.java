package sh.zolt.cli.command.publish;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.CommandProgress;
import sh.zolt.cli.command.CommandBuildProvenance;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.console.ProgressWriter;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectVersionOverride;
import sh.zolt.release.archive.ReleaseArchiveException;
import sh.zolt.release.archive.ReleaseArchiveResult;
import sh.zolt.release.archive.ReleaseArchiveService;
import sh.zolt.release.ReleaseTarget;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "release-archive",
        description = "Assemble a release archive from a native binary.",
        hidden = true)
public final class ReleaseArchiveCommand implements Runnable {
    private final ZoltTomlParser tomlParser;
    private final ReleaseArchiveService releaseArchiveService;

    @Option(names = "--target", description = "Release target. Supported: macos-arm64, macos-x64, linux-arm64, linux-x64, windows-x64.")
    private String target;

    @Option(names = "--binary", description = "Path to the native binary to archive.")
    private Path binary;

    @Option(names = "--output", description = "Directory for release archives.")
    private Path outputDirectory = Path.of("dist");

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Spec
    private CommandSpec spec;

    public ReleaseArchiveCommand() {
        this(new ZoltTomlParser(), new ReleaseArchiveService(CommandBuildProvenance.source()));
    }

    ReleaseArchiveCommand(ZoltTomlParser tomlParser, ReleaseArchiveService releaseArchiveService) {
        this.tomlParser = tomlParser;
        this.releaseArchiveService = releaseArchiveService;
    }

    @Override
    public void run() {
        ProgressWriter progress = CommandProgress.human(spec);
        Path projectRoot = projectDirectory.path();
        try {
            ProjectConfig config = ProjectVersionOverride.apply(
                    tomlParser.parse(projectRoot.resolve("zolt.toml")));
            ReleaseTarget releaseTarget = target == null ? ReleaseTarget.current() : ReleaseTarget.fromId(target);
            Path nativeBinary = binary == null
                    ? defaultNativeBinary(config, releaseTarget)
                    : binary;
            progress.start("Assembling release archive");
            ReleaseArchiveResult result = releaseArchiveService.assemble(
                    projectRoot,
                    config,
                    releaseTarget,
                    nativeBinary,
                    outputDirectory);
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            output.summary(
                    "Assembled " + result.target().id() + " release archive",
                    result.fileCount() + " files",
                    "root " + result.rootDirectory());
            output.pointers(
                    "wrote",
                    result.archivePath().toString(),
                    result.checksumPath().toString(),
                    result.manifestPath().toString());
            progress.result("Assembled " + result.target().id() + " release archive");
        } catch (ReleaseArchiveException | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private static Path defaultNativeBinary(ProjectConfig config, ReleaseTarget target) {
        String imageName = config.nativeSettings()
                .withDefaultImageName(config.project().name())
                .imageName();
        String binaryName = target == ReleaseTarget.WINDOWS_X64 && !imageName.endsWith(".exe")
                ? imageName + ".exe"
                : imageName;
        return Path.of(config.nativeSettings().output()).resolve(binaryName);
    }
}
