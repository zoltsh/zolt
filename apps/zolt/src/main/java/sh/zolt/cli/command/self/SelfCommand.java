package sh.zolt.cli.command.self;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.update.NativeInstallCommandSupport;
import sh.zolt.home.UserGlobalDirectory;
import sh.zolt.release.archive.ReleaseArchiveException;
import sh.zolt.release.channel.ReleaseChannelManifestException;
import sh.zolt.release.channel.ReleaseDistributionUrlLayout;
import sh.zolt.release.update.NativeInstalledVersion;
import sh.zolt.release.update.NativeUpdateException;
import sh.zolt.release.update.NativeUpdateResult;
import sh.zolt.release.update.NativeUpdateService;
import sh.zolt.release.update.NativeVersionListRequest;
import sh.zolt.release.update.NativeVersionListResult;
import sh.zolt.release.update.NativeVersionService;
import sh.zolt.release.update.NativeVersionSwitchRequest;
import sh.zolt.release.update.NativeVersionSwitchResult;
import java.io.IOException;
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

@Command(
        name = "self",
        description = "Manage the installer-managed Zolt binary.",
        subcommands = {
                SelfCommand.UpdateCommand.class,
                SelfCommand.VersionsCommand.class,
                SelfReleasesCommand.class,
                SelfInstallCommand.class,
                SelfExecCommand.class,
                SelfCommand.UseCommand.class,
                SelfCommand.RollbackCommand.class,
                SelfPruneCommand.class,
                SelfCommand.ChannelCommand.class
        })
public final class SelfCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    public static class NativeSelfOptions {
        @Option(names = "--install-root", hidden = true)
        private Path installRoot = UserGlobalDirectory.root();

        @Option(names = "--current-executable", hidden = true)
        private Path currentExecutable;

        Path installRoot() {
            return installRoot;
        }

        Path currentExecutable() {
            return NativeInstallCommandSupport.effectiveCurrentExecutable(currentExecutable);
        }

        NativeVersionListRequest listRequest() {
            return new NativeVersionListRequest(installRoot, currentExecutable());
        }

        NativeVersionSwitchRequest switchRequest(String version) {
            return new NativeVersionSwitchRequest(installRoot, currentExecutable(), version);
        }
    }

    @Command(name = "update", description = "Update the installer-managed Zolt binary.")
    public static final class UpdateCommand implements Callable<Integer> {
        private final NativeUpdateService nativeUpdateService;

        @Option(names = "--install-root", hidden = true)
        private Path installRoot = UserGlobalDirectory.root();

        @Option(names = "--channel-url", hidden = true)
        private String channelUrl;

        @Option(names = "--target", hidden = true)
        private String target;

        @Option(names = "--current-executable", hidden = true)
        private Path currentExecutable;

        @Option(names = "--work-dir", hidden = true)
        private Path workDirectory;

        @Spec
        private CommandSpec spec;

        public UpdateCommand() {
            this(new NativeUpdateService());
        }

        UpdateCommand(NativeUpdateService nativeUpdateService) {
            this.nativeUpdateService = nativeUpdateService;
        }

        @Override
        public Integer call() {
            try {
                NativeUpdateResult result = NativeInstallCommandSupport.update(
                        nativeUpdateService,
                        installRoot,
                        currentExecutable,
                        channelUrl,
                        target,
                        workDirectory);
                NativeInstallCommandSupport.printUpdate(spec, result);
                return 0;
            } catch (IllegalArgumentException
                    | NativeUpdateException
                    | ReleaseArchiveException
                    | ReleaseChannelManifestException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }
    }

    @Command(name = "versions", description = "List installed native Zolt versions.")
    public static final class VersionsCommand extends NativeSelfOptions implements Callable<Integer> {
        private final NativeVersionService nativeVersionService;

        @Spec
        private CommandSpec spec;

        public VersionsCommand() {
            this(new NativeVersionService());
        }

        VersionsCommand(NativeVersionService nativeVersionService) {
            this.nativeVersionService = nativeVersionService;
        }

        @Override
        public Integer call() {
            try {
                print(nativeVersionService.list(listRequest()));
                return 0;
            } catch (NativeUpdateException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }

        private void print(NativeVersionListResult result) {
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            output.summary("Installed native Zolt versions", "current " + result.currentVersion());
            for (NativeInstalledVersion version : result.versions()) {
                String marker = version.current() ? "*" : " ";
                String suffix = version.current() ? " current" : "";
                output.line("  " + marker + " " + version.version() + suffix);
            }
        }
    }

    @Command(name = "use", description = "Switch to an installed native Zolt version.")
    public static final class UseCommand extends NativeSelfOptions implements Callable<Integer> {
        private final NativeVersionService nativeVersionService;

        @Parameters(index = "0", paramLabel = "<VERSION>", description = "Installed version to make current.")
        private String version;

        @Spec
        private CommandSpec spec;

        public UseCommand() {
            this(new NativeVersionService());
        }

        UseCommand(NativeVersionService nativeVersionService) {
            this.nativeVersionService = nativeVersionService;
        }

        @Override
        public Integer call() {
            try {
                print(nativeVersionService.use(switchRequest(version)));
                return 0;
            } catch (NativeUpdateException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }

        private void print(NativeVersionSwitchResult result) {
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            if (result.switched()) {
                output.summary("Switched native Zolt to " + result.currentVersion(), "from " + result.previousVersion());
                output.pointer("wrote", result.executable().toString());
                output.next("Run `zolt --version` to confirm the active native executable.");
                return;
            }
            output.summary("Zolt is already current at " + result.currentVersion());
            output.pointer("kept", result.executable().toString());
        }
    }

    @Command(name = "rollback", description = "Switch back to the previous native Zolt version.")
    public static final class RollbackCommand extends NativeSelfOptions implements Callable<Integer> {
        private final NativeVersionService nativeVersionService;

        @Spec
        private CommandSpec spec;

        public RollbackCommand() {
            this(new NativeVersionService());
        }

        RollbackCommand(NativeVersionService nativeVersionService) {
            this.nativeVersionService = nativeVersionService;
        }

        @Override
        public Integer call() {
            try {
                print(nativeVersionService.rollback(listRequest()));
                return 0;
            } catch (NativeUpdateException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }

        private void print(NativeVersionSwitchResult result) {
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            output.summary("Rolled native Zolt back to " + result.currentVersion(), "from " + result.previousVersion());
            output.pointer("wrote", result.executable().toString());
            output.next("Run `zolt --version` to confirm the active native executable.");
        }
    }

    @Command(name = "channel", description = "Show or change the native release channel.")
    public static final class ChannelCommand extends NativeSelfOptions implements Callable<Integer> {
        private static final Set<String> SUPPORTED_CHANNELS = Set.of("stable", "preview", "zap");

        @Parameters(
                index = "0",
                arity = "0..1",
                paramLabel = "<CHANNEL>",
                description = "Channel to write: stable, preview, or zap.")
        private String channel;

        @Option(names = "--origin", hidden = true)
        private String origin = ReleaseDistributionUrlLayout.DEFAULT_ORIGIN;

        @Spec
        private CommandSpec spec;

        @Override
        public Integer call() {
            try {
                if (channel == null || channel.isBlank()) {
                    printCurrent();
                    return 0;
                }
                setChannel(normalizeChannel(channel));
                return 0;
            } catch (IOException | IllegalArgumentException | ReleaseChannelManifestException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }

        private void printCurrent() throws IOException {
            Path root = installRoot().toAbsolutePath().normalize();
            String currentChannel = read(root.resolve("channel"), "stable");
            String currentChannelUrl = read(
                    root.resolve("channel-url"),
                    new ReleaseDistributionUrlLayout(origin).channelManifestUrl(currentChannel));
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            output.context("install root", root.toString());
            output.context("channel", currentChannel);
            output.context("channel url", currentChannelUrl);
        }

        private void setChannel(String normalizedChannel) throws IOException {
            Path root = installRoot().toAbsolutePath().normalize();
            Files.createDirectories(root);
            String channelUrl = new ReleaseDistributionUrlLayout(origin).channelManifestUrl(normalizedChannel);
            Files.writeString(root.resolve("channel"), normalizedChannel + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.writeString(root.resolve("channel-url"), channelUrl + System.lineSeparator(), StandardCharsets.UTF_8);

            CommandHumanOutput output = CommandHumanOutput.of(spec);
            output.summary("Set native Zolt channel to " + normalizedChannel);
            output.pointer("wrote", root.resolve("channel").toString());
            output.pointer("wrote", root.resolve("channel-url").toString());
            output.next("Run `zolt self update` to download the latest " + normalizedChannel + " build.");
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
}
