package sh.zolt.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.IntSupplier;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;
import sh.zolt.cli.command.ProjectCommandMutationLock;

/** Keeps finite project command phases in one lease; running applications release build leases. */
final class CommandMutationExecution {
    private static final Set<String> COMMANDS = Set.of(
            "build", "clean", "package", "native", "resolve", "test", "integration-test", "coverage",
            "publish", "add", "remove", "update", "versions", "platforms", "bom");

    private CommandMutationExecution() {}

    static int execute(ParseResult root, IntSupplier action) {
        if (!root.hasSubcommand() || helpRequested(root)) {
            return action.getAsInt();
        }
        ParseResult command = root.subcommand();
        if (!COMMANDS.contains(command.commandSpec().name())) {
            return action.getAsInt();
        }
        while (command.hasSubcommand()) {
            command = command.subcommand();
        }
        Path directory = (Path) value(command, "--directory", value(command, "--cwd", Path.of(".")));
        if (!Files.isDirectory(directory)) {
            return action.getAsInt();
        }
        try {
            return ProjectCommandMutationLock.withLock(directory, ignored -> action.getAsInt());
        } catch (CommandLine.ExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // This phase runs before RunLast, which normally wraps command failures for Picocli.
            // Preserve the selected command and cause so its standard human/JSON handler renders
            // discovery and lock failures just like failures raised inside the command itself.
            throw new CommandLine.ExecutionException(
                    command.commandSpec().commandLine(), exception.getMessage(), exception);
        }
    }

    private static boolean helpRequested(ParseResult result) {
        return result.isUsageHelpRequested() || result.isVersionHelpRequested()
                || (result.hasSubcommand() && helpRequested(result.subcommand()));
    }

    private static Object value(ParseResult command, String option, Object fallback) {
        var spec = command.commandSpec().findOption(option);
        if (spec == null) {
            return fallback;
        }
        Object value = spec.getValue();
        return value == null ? fallback : value;
    }
}
