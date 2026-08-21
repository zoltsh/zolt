package sh.zolt.cli;

import sh.zolt.home.UserGlobalDirectory;
import sh.zolt.toolchain.ZoltVersionPinReader;
import sh.zolt.toolchain.ZoltVersionRequirement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParseResult;

final class ZoltToolchainNoticeHook {
    @Option(names = "--toolchain-check", scope = CommandLine.ScopeType.INHERIT, hidden = true)
    private String toolchainCheck = "auto";

    @Option(names = "--toolchain-check-cwd", scope = CommandLine.ScopeType.INHERIT, hidden = true)
    private Path toolchainCheckDirectory;

    @Option(names = "--toolchain-check-install-root", scope = CommandLine.ScopeType.INHERIT, hidden = true)
    private Path toolchainCheckInstallRoot = UserGlobalDirectory.root();

    void printAfterSuccess(CommandLine commandLine, ParseResult parseResult, boolean quiet) {
        if (shouldSkip(parseResult, quiet)) {
            return;
        }
        boolean force = mode().equals("always");
        if (!force && System.console() == null) {
            return;
        }
        try {
            new ZoltVersionPinReader()
                    .find(workingDirectory())
                    .filter(requirement -> !requirement.zoltVersion().equals(ZoltCli.version()))
                    .map(this::message)
                    .ifPresent(message -> {
                        commandLine.getErr().println(message);
                        commandLine.getErr().flush();
                    });
        } catch (RuntimeException exception) {
            // Toolchain notices are advisory and must not fail the user's command.
        }
    }

    private boolean shouldSkip(ParseResult parseResult, boolean quiet) {
        if (quiet || mode().equals("never") || parseResult.isUsageHelpRequested() || parseResult.isVersionHelpRequested()) {
            return true;
        }
        List<String> commandPath = commandPath(parseResult);
        return commandPath.isEmpty()
                || commandPath.contains("help")
                || commandPath.contains("self");
    }

    private String message(ZoltVersionRequirement requirement) {
        String version = requirement.zoltVersion();
        String action = installed(version)
                ? "Run `zolt self use " + version + "` to switch to it."
                : "Run `zolt self install " + version + "` to download it.";
        return "This project wants Zolt "
                + version
                + ", but this command is running "
                + ZoltCli.version()
                + ". "
                + action;
    }

    private boolean installed(String version) {
        Path binary = toolchainCheckInstallRoot
                .resolve("versions")
                .resolve(version)
                .resolve("bin")
                .resolve("zolt")
                .toAbsolutePath()
                .normalize();
        return Files.isExecutable(binary);
    }

    private Path workingDirectory() {
        return toolchainCheckDirectory == null
                ? Path.of("").toAbsolutePath().normalize()
                : toolchainCheckDirectory;
    }

    private String mode() {
        String normalized = toolchainCheck.toLowerCase(Locale.ROOT).strip();
        if (normalized.equals("always") || normalized.equals("never") || normalized.equals("auto")) {
            return normalized;
        }
        return "auto";
    }

    private static List<String> commandPath(ParseResult parseResult) {
        ArrayList<String> names = new ArrayList<>();
        ParseResult current = parseResult;
        while (current != null) {
            names.add(current.commandSpec().name());
            current = current.hasSubcommand() ? current.subcommand() : null;
        }
        return List.copyOf(names);
    }
}
