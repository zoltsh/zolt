package sh.zolt.cli.command.self;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.release.ReleaseTarget;
import sh.zolt.release.channel.ReleaseChannelManifestException;
import sh.zolt.release.channel.ReleaseDistributionUrlLayout;
import sh.zolt.release.update.NativeUpdateException;
import sh.zolt.release.update.NativeVersionInstallRequest;
import sh.zolt.release.update.NativeVersionInstallResult;
import sh.zolt.release.update.NativeVersionInstallService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "install", description = "Install a native Zolt version without switching to it.")
public final class SelfInstallCommand extends SelfCommand.NativeSelfOptions implements Callable<Integer> {
    private static final Set<String> SUPPORTED_CHANNELS = Set.of("stable", "preview", "zap");

    private final NativeVersionInstallService installService;

    @Parameters(index = "0", paramLabel = "<VERSION>", description = "Remote version to install.")
    private String version;

    @Option(names = "--channel", description = "Release channel to use: stable, preview, or zap.")
    private String channel;

    @Option(names = "--target", hidden = true)
    private String target;

    @Option(names = "--origin", hidden = true)
    private String origin = ReleaseDistributionUrlLayout.DEFAULT_ORIGIN;

    @Option(names = "--release-index-url", hidden = true)
    private String releaseIndexUrl;

    @Option(names = "--work-dir", hidden = true)
    private Path workDirectory;

    @Spec
    private CommandSpec spec;

    public SelfInstallCommand() {
        this(new NativeVersionInstallService());
    }

    SelfInstallCommand(NativeVersionInstallService installService) {
        this.installService = installService;
    }

    @Override
    public Integer call() {
        try {
            ReleaseTarget releaseTarget = target == null ? ReleaseTarget.current() : ReleaseTarget.fromId(target);
            NativeVersionInstallResult result = installService.install(new NativeVersionInstallRequest(
                    installRoot(),
                    currentExecutable(),
                    releaseIndexUri(),
                    version,
                    releaseTarget,
                    workDirectory));
            print(result);
            return 0;
        } catch (IOException | IllegalArgumentException | NativeUpdateException | ReleaseChannelManifestException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private void print(NativeVersionInstallResult result) {
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        if (result.installed()) {
            output.summary(
                    "Installed native Zolt " + result.version(),
                    result.channel() + " channel",
                    result.target().id());
            output.pointer("wrote", result.executable().toString());
            output.next("Run `zolt self use " + result.version() + "` to switch to it.");
            return;
        }
        output.summary(
                "Native Zolt " + result.version() + " is already installed",
                result.channel() + " channel",
                result.target().id());
        output.pointer("kept", result.executable().toString());
    }

    private URI releaseIndexUri() throws IOException {
        if (releaseIndexUrl != null && !releaseIndexUrl.isBlank()) {
            return URI.create(releaseIndexUrl);
        }
        String effectiveChannel = channel == null || channel.isBlank()
                ? read(installRoot().resolve("channel"), "stable")
                : channel;
        return URI.create(new ReleaseDistributionUrlLayout(origin).releaseIndexUrl(normalizeChannel(effectiveChannel)));
    }

    private static String normalizeChannel(String value) {
        String normalized = value.strip();
        if (!SUPPORTED_CHANNELS.contains(normalized)) {
            throw new IllegalArgumentException("Native Zolt channel must be one of stable, preview, zap.");
        }
        return normalized;
    }

    private static String read(Path path, String fallback) throws IOException {
        if (!Files.isRegularFile(path)) {
            return fallback;
        }
        String value = Files.readString(path, StandardCharsets.UTF_8).strip();
        return value.isBlank() ? fallback : value;
    }
}
