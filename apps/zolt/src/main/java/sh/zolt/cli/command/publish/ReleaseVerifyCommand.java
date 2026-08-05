package sh.zolt.cli.command.publish;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.CommandProgress;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.console.ProgressWriter;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectVersionOverride;
import sh.zolt.release.verification.ReleaseVerificationException;
import sh.zolt.release.verification.ReleaseVerificationResult;
import sh.zolt.release.verification.ReleaseVerificationService;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "release-verify",
        description = "Verify release archives by unpacking and smoking the binary.",
        hidden = true)
public final class ReleaseVerifyCommand implements Runnable {
    private final ZoltTomlParser tomlParser;
    private final ReleaseVerificationService releaseVerificationService;

    @Parameters(arity = "1..*", paramLabel = "ARCHIVE", description = "Release archive path to verify.")
    private List<Path> archives;

    @Option(names = "--work-dir", description = "Directory for unpacked verification work.")
    private Path workDirectory;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Spec
    private CommandSpec spec;

    public ReleaseVerifyCommand() {
        this(new ZoltTomlParser(), new ReleaseVerificationService());
    }

    ReleaseVerifyCommand(ZoltTomlParser tomlParser, ReleaseVerificationService releaseVerificationService) {
        this.tomlParser = tomlParser;
        this.releaseVerificationService = releaseVerificationService;
    }

    @Override
    public void run() {
        ProgressWriter progress = CommandProgress.human(spec);
        Path projectRoot = projectDirectory.path();
        try {
            ProjectConfig config = ProjectVersionOverride.apply(
                    tomlParser.parse(projectRoot.resolve("zolt.toml")));
            List<Path> resolvedArchives = archives.stream()
                    .map(path -> projectRoot.resolve(path).normalize())
                    .toList();
            progress.start("Verifying release archives");
            ReleaseVerificationResult result = releaseVerificationService.verify(
                    resolvedArchives,
                    projectRoot.resolve(effectiveWorkDirectory(config)).normalize(),
                    config.project().version());
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            output.status("Release verify status", "ok");
            for (ReleaseVerificationResult.VerifiedArchive archive : result.archives()) {
                output.statusDetail("ok", "Verified release archive " + archive.archivePath());
                output.context("Unpacked to", archive.unpackDirectory().toString());
                output.statusDetail("ok", "Ran smoke binary " + archive.binaryPath());
            }
            output.context("Archives verified", Integer.toString(result.verifiedCount()));
            progress.result("Verified " + result.verifiedCount() + " release archives");
        } catch (ReleaseVerificationException | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private Path effectiveWorkDirectory(ProjectConfig config) {
        if (workDirectory != null) {
            return workDirectory;
        }
        String outputRoot = config.build().outputRoot();
        String effectiveOutputRoot = outputRoot == null || outputRoot.isBlank() ? "target" : outputRoot;
        return Path.of(effectiveOutputRoot).resolve("release-verify");
    }
}
