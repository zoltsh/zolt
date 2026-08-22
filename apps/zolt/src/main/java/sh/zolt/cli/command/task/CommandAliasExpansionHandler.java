package sh.zolt.cli.command.task;

import sh.zolt.command.CommandAlias;
import sh.zolt.command.CommandConfig;
import sh.zolt.toml.ZoltConfigException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.ParameterException;

public final class CommandAliasExpansionHandler implements IParameterExceptionHandler {
    @Override
    public int handleParseException(ParameterException exception, String[] args) {
        CommandLine commandLine = exception.getCommandLine();
        if (!(exception instanceof CommandLine.UnmatchedArgumentException)) {
            return printDefault(exception, commandLine, false);
        }

        int aliasIndex = firstCommandToken(args);
        if (aliasIndex < 0 || commandLine.getSubcommands().containsKey(args[aliasIndex])) {
            return printDefault(exception, commandLine, false);
        }

        CommandConfig config;
        try {
            // The effective namespace, so a member-declared alias expands from the member directory
            // exactly as a root-declared one does (design §4.5).
            config = new CommandConfigRoots().load(startDirectory(args)).config();
        } catch (ZoltConfigException configException) {
            return printDefault(exception, commandLine, false);
        }

        CommandAlias alias = config.aliases().get(args[aliasIndex]);
        if (alias == null) {
            return printDefault(exception, commandLine, !config.aliases().isEmpty());
        }

        String[] expanded = expandedArgs(args, aliasIndex, alias);
        return commandLine.execute(expanded);
    }

    private static String[] expandedArgs(String[] args, int aliasIndex, CommandAlias alias) {
        List<String> expanded = new ArrayList<>();
        for (int index = 0; index < aliasIndex; index++) {
            expanded.add(args[index]);
        }
        expanded.addAll(alias.argv());
        for (int index = aliasIndex + 1; index < args.length; index++) {
            expanded.add(args[index]);
        }
        return expanded.toArray(String[]::new);
    }

    private static int printDefault(ParameterException exception, CommandLine commandLine, boolean mentionAliases) {
        PrintWriter err = commandLine.getErr();
        err.println(exception.getMessage());
        if (mentionAliases) {
            err.println("Run `zolt aliases` to list configured aliases.");
        }
        err.flush();
        return commandLine.getCommandSpec().exitCodeOnInvalidInput();
    }

    private static Path startDirectory(String[] args) {
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if (arg.startsWith("--cwd=")) {
                return Path.of(arg.substring("--cwd=".length()));
            }
            if (arg.startsWith("--directory=")) {
                return Path.of(arg.substring("--directory=".length()));
            }
            if (("--cwd".equals(arg) || "--directory".equals(arg)) && index + 1 < args.length) {
                return Path.of(args[index + 1]);
            }
        }
        return Path.of(".");
    }

    private static int firstCommandToken(String[] args) {
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--".equals(arg)) {
                return -1;
            }
            if (takesValue(arg)) {
                index++;
                continue;
            }
            if (isBooleanGlobalOption(arg) || hasInlineValue(arg)) {
                continue;
            }
            if (arg.startsWith("-")) {
                return -1;
            }
            return index;
        }
        return -1;
    }

    private static boolean takesValue(String arg) {
        return "--color".equals(arg)
                || "--progress".equals(arg)
                || "--cwd".equals(arg)
                || "--directory".equals(arg)
                || "--update-check-install-root".equals(arg)
                || "--update-check-channel-url".equals(arg)
                || "--update-check-target".equals(arg)
                || "--update-check-current-executable".equals(arg)
                || "--update-check-state-dir".equals(arg)
                || "--update-check-interval-seconds".equals(arg)
                || "--toolchain-check".equals(arg)
                || "--toolchain-check-cwd".equals(arg)
                || "--toolchain-check-install-root".equals(arg);
    }

    private static boolean hasInlineValue(String arg) {
        return arg.startsWith("--color=")
                || arg.startsWith("--progress=")
                || arg.startsWith("--cwd=")
                || arg.startsWith("--directory=")
                || arg.startsWith("--update-check-install-root=")
                || arg.startsWith("--update-check-channel-url=")
                || arg.startsWith("--update-check-target=")
                || arg.startsWith("--update-check-current-executable=")
                || arg.startsWith("--update-check-state-dir=")
                || arg.startsWith("--update-check-interval-seconds=")
                || arg.startsWith("--toolchain-check=")
                || arg.startsWith("--toolchain-check-cwd=")
                || arg.startsWith("--toolchain-check-install-root=");
    }

    private static boolean isBooleanGlobalOption(String arg) {
        return "--no-progress".equals(arg)
                || "--quiet".equals(arg)
                || "-q".equals(arg)
                || "--list".equals(arg)
                || "--help".equals(arg)
                || "-h".equals(arg)
                || "--version".equals(arg)
                || "-V".equals(arg)
                || "--update-check".equals(arg)
                || "--internal-enable-update-notices".equals(arg);
    }
}
