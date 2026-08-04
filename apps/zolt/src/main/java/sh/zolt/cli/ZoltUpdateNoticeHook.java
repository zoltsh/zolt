package sh.zolt.cli;

import sh.zolt.cli.command.update.NativeInstallCommandSupport;
import sh.zolt.release.ReleaseTarget;
import sh.zolt.release.update.NativeUpdateNotice;
import sh.zolt.release.update.NativeUpdateNoticeRequest;
import sh.zolt.release.update.NativeUpdateNoticeService;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParseResult;

final class ZoltUpdateNoticeHook {
    @Option(names = "--update-check", scope = CommandLine.ScopeType.INHERIT, hidden = true)
    private String updateCheck = "never";

    @Option(names = "--update-check-install-root", scope = CommandLine.ScopeType.INHERIT, hidden = true)
    private Path updateCheckInstallRoot;

    @Option(names = "--update-check-channel-url", scope = CommandLine.ScopeType.INHERIT, hidden = true)
    private String updateCheckChannelUrl;

    @Option(names = "--update-check-target", scope = CommandLine.ScopeType.INHERIT, hidden = true)
    private String updateCheckTarget;

    @Option(names = "--update-check-current-executable", scope = CommandLine.ScopeType.INHERIT, hidden = true)
    private Path updateCheckCurrentExecutable;

    @Option(names = "--update-check-state-dir", scope = CommandLine.ScopeType.INHERIT, hidden = true)
    private Path updateCheckStateDirectory;

    @Option(names = "--update-check-interval-seconds", scope = CommandLine.ScopeType.INHERIT, hidden = true)
    private long updateCheckIntervalSeconds = 86_400;

    @Option(names = "--internal-enable-update-notices", scope = CommandLine.ScopeType.INHERIT, hidden = true)
    private boolean internalEnableUpdateNotices;

    void printAfterSuccess(CommandLine commandLine, ParseResult parseResult, boolean quiet) {
        printUpdateNotice(commandLine, parseResult, quiet);
    }

    private void printUpdateNotice(CommandLine commandLine, ParseResult parseResult, boolean quiet) {
        if (parseResult.isUsageHelpRequested()
                || parseResult.isVersionHelpRequested()
                || !internalEnableUpdateNotices
                || shouldSkipUpdateNotice(parseResult, quiet)) {
            return;
        }
        boolean force = updateCheckMode().equals("always");
        if (!force && System.console() == null) {
            return;
        }
        Path currentExecutable = effectiveUpdateCheckExecutable();
        if (currentExecutable == null) {
            return;
        }
        try {
            Path installRoot = NativeInstallCommandSupport.effectiveInstallRoot(
                    updateCheckInstallRoot, currentExecutable);
            ReleaseTarget target = updateCheckTarget == null ? ReleaseTarget.current() : ReleaseTarget.fromId(updateCheckTarget);
            NativeUpdateNoticeService service = new NativeUpdateNoticeService();
            service.check(new NativeUpdateNoticeRequest(
                            installRoot,
                            currentExecutable,
                            URI.create(NativeInstallCommandSupport.effectiveChannelUrl(
                                    installRoot, updateCheckChannelUrl)),
                            target,
                            updateCheckStateDirectory == null ? installRoot.resolve("state") : updateCheckStateDirectory,
                            Instant.now(),
                            Duration.ofSeconds(Math.max(0, updateCheckIntervalSeconds)),
                            updateCheckDisabled(),
                            updateCheckOffline(),
                            !force && updateCheckCi(),
                            force || System.console() != null))
                    .map(NativeUpdateNotice::message)
                    .ifPresent(message -> {
                        commandLine.getErr().println(message);
                        commandLine.getErr().flush();
                    });
        } catch (RuntimeException exception) {
            // Passive update notices must never fail the user's original command.
        }
    }

    private boolean shouldSkipUpdateNotice(ParseResult parseResult, boolean quiet) {
        if (quiet || updateCheckMode().equals("never")) {
            return true;
        }
        String commandName = leafCommandName(parseResult);
        return commandName.equals("zolt")
                || commandName.equals("help")
                || commandName.equals("update");
    }

    private static String leafCommandName(ParseResult parseResult) {
        ParseResult current = parseResult;
        while (current.hasSubcommand()) {
            current = current.subcommand();
        }
        return current.commandSpec().name();
    }

    private Path effectiveUpdateCheckExecutable() {
        if (updateCheckCurrentExecutable != null) {
            return updateCheckCurrentExecutable;
        }
        return ProcessHandle.current()
                .info()
                .command()
                .map(Path::of)
                .orElse(null);
    }

    private String updateCheckMode() {
        String normalized = updateCheck.toLowerCase(Locale.ROOT).strip();
        if (normalized.equals("always") || normalized.equals("never") || normalized.equals("auto")) {
            return normalized;
        }
        return "auto";
    }

    private boolean updateCheckDisabled() {
        String env = System.getenv().getOrDefault("ZOLT_UPDATE_CHECK", "").toLowerCase(Locale.ROOT).strip();
        return updateCheckMode().equals("never")
                || env.equals("0")
                || env.equals("off")
                || env.equals("false")
                || env.equals("never")
                || env.equals("disabled");
    }

    private boolean updateCheckOffline() {
        String env = System.getenv().getOrDefault("ZOLT_OFFLINE", "").toLowerCase(Locale.ROOT).strip();
        return env.equals("1") || env.equals("true") || env.equals("yes");
    }

    private boolean updateCheckCi() {
        return System.getenv().containsKey("CI") || System.getenv().containsKey("GITHUB_ACTIONS");
    }
}
