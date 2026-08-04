package sh.zolt.cli.command.self;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.release.channel.ReleaseChannelManifestException;
import sh.zolt.release.channel.ReleaseDistributionUrlLayout;
import sh.zolt.release.update.NativeReleaseIndexService;
import sh.zolt.release.update.NativeReleaseListRequest;
import sh.zolt.release.update.NativeReleaseListResult;
import sh.zolt.release.update.NativeReleaseVersion;
import sh.zolt.release.update.NativeUpdateException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "releases", description = "List remote native Zolt releases.")
public final class SelfReleasesCommand extends SelfCommand.NativeSelfOptions implements Callable<Integer> {
    private static final Set<String> SUPPORTED_CHANNELS = Set.of("stable", "preview", "zap");

    private final NativeReleaseIndexService releaseIndexService;

    @Option(names = "--channel", description = "Release channel to list: stable, preview, or zap.")
    private String channel;

    @Option(names = "--origin", hidden = true)
    private String origin = ReleaseDistributionUrlLayout.DEFAULT_ORIGIN;

    @Option(names = "--release-index-url", hidden = true)
    private String releaseIndexUrl;

    @Spec
    private CommandSpec spec;

    public SelfReleasesCommand() {
        this(new NativeReleaseIndexService());
    }

    SelfReleasesCommand(NativeReleaseIndexService releaseIndexService) {
        this.releaseIndexService = releaseIndexService;
    }

    @Override
    public Integer call() {
        try {
            URI indexUri = releaseIndexUri();
            print(indexUri, releaseIndexService.list(new NativeReleaseListRequest(indexUri)));
            return 0;
        } catch (IOException | IllegalArgumentException | NativeUpdateException | ReleaseChannelManifestException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private void print(URI indexUri, NativeReleaseListResult result) {
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        output.summary("Remote native Zolt releases", result.channel(), result.releases().size() + " versions");
        output.context("release index", indexUri.toString());
        for (NativeReleaseVersion release : result.releases()) {
            output.line("  - " + release.version() + " (" + release.createdAt() + ")");
            output.line("    targets: " + release.targets().stream()
                    .map(target -> target.id())
                    .collect(Collectors.joining(", ")));
        }
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
