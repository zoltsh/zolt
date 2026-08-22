package sh.zolt.cli.command.nativeimage;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectVersionOverride;
import sh.zolt.selfhost.NativeSmokeException;
import sh.zolt.selfhost.NativeSmokeResult;
import sh.zolt.selfhost.NativeSmokeService;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "native-smoke",
        description = "Smoke a native Zolt binary against real workflows.",
        hidden = true)
public final class NativeSmokeCommand implements Runnable {
    private final ManifestProjectLoader projectLoader;
    private final NativeSmokeService nativeSmokeService;

    @Option(names = "--binary", required = true, description = "Native Zolt binary to smoke.")
    private Path binary;

    @Option(names = "--work-dir", description = "Directory for native smoke work.")
    private Path workDirectory;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Spec
    private CommandSpec spec;

    public NativeSmokeCommand() {
        this(new ManifestProjectLoader(), new NativeSmokeService());
    }

    NativeSmokeCommand(ManifestProjectLoader projectLoader, NativeSmokeService nativeSmokeService) {
        this.projectLoader = projectLoader;
        this.nativeSmokeService = nativeSmokeService;
    }

    @Override
    public void run() {
        Path projectRoot = projectDirectory.path();
        try {
            ProjectConfig config = ProjectVersionOverride.apply(
                    projectLoader.load(projectRoot));
            NativeSmokeResult result = nativeSmokeService.smoke(
                    projectRoot,
                    config,
                    binary,
                    effectiveWorkDirectory(config));
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            output.status("Native smoke status", "ok");
            output.statusDetail("ok", "Smoked binary " + result.binary());
            output.statusDetail("ok", "Verified release archive " + result.archive());
            output.statusDetail("ok", "Ran generated project " + result.projectDirectory());
        } catch (NativeSmokeException | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private Path effectiveWorkDirectory(ProjectConfig config) {
        if (workDirectory != null) {
            return workDirectory;
        }
        String outputRoot = config.build().outputRoot();
        String effectiveOutputRoot = outputRoot == null || outputRoot.isBlank() ? "target" : outputRoot;
        return Path.of(effectiveOutputRoot).resolve("native-smoke");
    }
}
