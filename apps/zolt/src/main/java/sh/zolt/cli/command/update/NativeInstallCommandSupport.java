package sh.zolt.cli.command.update;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.home.UserGlobalDirectory;
import sh.zolt.release.ReleaseTarget;
import sh.zolt.release.channel.ReleaseDistributionUrlLayout;
import sh.zolt.release.update.NativeUpdateException;
import sh.zolt.release.update.NativeUpdateRequest;
import sh.zolt.release.update.NativeUpdateResult;
import sh.zolt.release.update.NativeUpdateService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Model.CommandSpec;

public final class NativeInstallCommandSupport {
    private NativeInstallCommandSupport() {
    }

    public static NativeUpdateResult update(
            NativeUpdateService nativeUpdateService,
            Path installRoot,
            Path currentExecutable,
            String channelUrl,
            String target,
            Path workDirectory) {
        ReleaseTarget releaseTarget = target == null ? ReleaseTarget.current() : ReleaseTarget.fromId(target);
        Path executable = effectiveCurrentExecutable(currentExecutable);
        Path root = effectiveInstallRoot(installRoot, executable);
        return nativeUpdateService.update(new NativeUpdateRequest(
                root,
                executable,
                URI.create(effectiveChannelUrl(root, channelUrl)),
                releaseTarget,
                workDirectory));
    }

    public static Path effectiveCurrentExecutable(Path currentExecutable) {
        if (currentExecutable != null) {
            return currentExecutable;
        }
        return ProcessHandle.current()
                .info()
                .command()
                .map(Path::of)
                .orElseThrow(() -> new NativeUpdateException(
                        "Installer-managed native Zolt operations could not determine the current executable. Reinstall with the native installer."));
    }

    public static Path effectiveInstallRoot(Path installRoot, Path currentExecutable) {
        if (installRoot != null) {
            return installRoot;
        }
        Path executable = effectiveCurrentExecutable(currentExecutable).toAbsolutePath().normalize();
        try {
            executable = executable.toRealPath();
        } catch (IOException exception) {
            return UserGlobalDirectory.root();
        }
        Path binDirectory = executable.getParent();
        Path versionDirectory = binDirectory == null ? null : binDirectory.getParent();
        Path versionsDirectory = versionDirectory == null ? null : versionDirectory.getParent();
        Path root = versionsDirectory == null ? null : versionsDirectory.getParent();
        if (root == null
                || !executable.getFileName().toString().equals("zolt")
                || !binDirectory.getFileName().toString().equals("bin")
                || !versionsDirectory.getFileName().toString().equals("versions")) {
            return UserGlobalDirectory.root();
        }
        Path binLink = root.resolve("bin").resolve("zolt");
        if (!Files.isSymbolicLink(binLink)) {
            return UserGlobalDirectory.root();
        }
        try {
            return binLink.toRealPath().equals(executable) ? root : UserGlobalDirectory.root();
        } catch (IOException exception) {
            return UserGlobalDirectory.root();
        }
    }

    public static String effectiveChannelUrl(Path installRoot, String channelUrl) {
        if (channelUrl != null && !channelUrl.isBlank()) {
            return channelUrl;
        }
        Path installedChannelUrl = installRoot.resolve("channel-url");
        if (Files.isRegularFile(installedChannelUrl)) {
            try {
                String value = Files.readString(installedChannelUrl, StandardCharsets.UTF_8).strip();
                if (!value.isBlank()) {
                    return value;
                }
            } catch (java.io.IOException exception) {
                // Fall back to the stable channel; update will still validate the install layout.
            }
        }
        return new ReleaseDistributionUrlLayout().channelManifestUrl("stable");
    }

    public static void printUpdate(CommandSpec spec, NativeUpdateResult result) {
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        if (result.updated()) {
            output.summary(
                    "Updated native Zolt to " + result.availableVersion(),
                    "from " + result.previousVersion(),
                    result.channel() + " channel",
                    result.target().id());
        } else {
            output.summary(
                    "Zolt is already current at " + result.previousVersion(),
                    result.channel() + " channel",
                    result.target().id());
        }
        output.pointer("wrote", result.executable().toString());
        if (result.updated()) {
            output.next("Run `zolt --version` to confirm the active native executable.");
        }
    }
}
