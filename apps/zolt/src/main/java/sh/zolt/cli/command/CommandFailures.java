package sh.zolt.cli.command;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.PrintedUserException;
import sh.zolt.error.ActionableError;
import sh.zolt.error.HasActionableError;
import java.util.Optional;
import java.util.Set;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;

public final class CommandFailures {
    private static final Set<String> STABLE_JSON_COMMANDS = Set.of(
            "check",
            "outdated",
            "update",
            "toolchain status",
            "toolchain global status");

    private CommandFailures() {
    }

    public static CommandLine.ExecutionException user(CommandSpec spec, Exception exception) {
        Optional<ActionableError> carrier = actionableError(exception);
        if (carrier.isPresent()) {
            printUser(spec, carrier.get());
        } else {
            printUser(spec, exception.getMessage());
        }
        return new PrintedUserException(spec.commandLine(), exception.getMessage());
    }

    public static CommandLine.ExecutionException user(CommandSpec spec, ActionableError error) {
        printUser(spec, error);
        return new PrintedUserException(spec.commandLine(), error.message());
    }

    public static CommandLine.ExecutionException user(CommandSpec spec, String displayMessage, Exception exception) {
        printUser(spec, displayMessage);
        return new PrintedUserException(spec.commandLine(), exception.getMessage());
    }

    public static void printUser(CommandSpec spec, Exception exception) {
        Optional<ActionableError> carrier = actionableError(exception);
        if (carrier.isPresent()) {
            printUser(spec, carrier.get());
        } else {
            printUser(spec, exception.getMessage());
        }
    }

    public static void printUser(CommandSpec spec, ActionableError error) {
        render(spec, CommandErrorBlock.of(error.summary(), error.remediation()));
    }

    /**
     * Renders the structured carrier when {@code throwable} (or its cause chain) supplies an
     * {@link ActionableError}, returning {@code true} when it did. Lets the root execution-exception
     * handler render thrown {@link sh.zolt.error.ActionableException}s through the structured path
     * while leaving non-actionable errors on their existing flat-message path.
     */
    public static boolean printActionable(CommandSpec spec, Throwable throwable) {
        Optional<ActionableError> carrier = actionableError(throwable);
        carrier.ifPresent(error -> printUser(spec, error));
        return carrier.isPresent();
    }

    public static void printUser(CommandSpec spec, String displayMessage) {
        render(spec, CommandErrorBlock.from(displayMessage));
    }

    private static void render(CommandSpec spec, CommandErrorBlock block) {
        if (machineReadable(spec)) {
            spec.commandLine().getOut().print(json(spec, block));
            spec.commandLine().getOut().flush();
            return;
        }
        CommandHumanOutput output = CommandHumanOutput.errors(spec);
        output.error(block.summary());
        if (!block.contextRows().isEmpty() || block.next().isPresent()) {
            output.blankLine();
        }
        for (CommandErrorBlock.ContextRow row : block.contextRows()) {
            output.context(row.label(), row.value());
        }
        block.next().ifPresent(output::next);
        spec.commandLine().getErr().flush();
    }

    private static boolean machineReadable(CommandSpec spec) {
        ParseResult parseResult = spec.commandLine().getParseResult();
        if (parseResult == null || !STABLE_JSON_COMMANDS.contains(commandName(spec))) {
            return false;
        }
        if (parseResult.hasMatchedOption("--json")
                && Boolean.TRUE.equals(parseResult.matchedOptionValue("--json", false))) {
            return true;
        }
        if (!parseResult.hasMatchedOption("--format")) {
            return false;
        }
        Object format = parseResult.matchedOptionValue("--format", null);
        return format != null && "json".equalsIgnoreCase(format.toString());
    }

    private static String json(CommandSpec spec, CommandErrorBlock block) {
        StringBuilder output = new StringBuilder();
        output.append("{\n")
                .append("  \"schemaVersion\": 1,\n")
                .append("  \"command\": ").append(quote(commandName(spec))).append(",\n")
                .append("  \"status\": \"failed\",\n")
                .append("  \"diagnostics\": [\n")
                .append("    {\n")
                .append("      \"severity\": \"error\",\n")
                .append("      \"message\": ").append(quote(block.summary())).append(",\n")
                .append("      \"nextStep\": ")
                .append(block.next().map(CommandFailures::quote).orElse("null"))
                .append("\n")
                .append("    }\n")
                .append("  ]\n")
                .append("}\n");
        return output.toString();
    }

    private static String commandName(CommandSpec spec) {
        String qualified = spec.qualifiedName();
        return qualified.startsWith("zolt ") ? qualified.substring("zolt ".length()) : qualified;
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static Optional<ActionableError> actionableError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof HasActionableError carrier && carrier.actionableError() != null) {
                return Optional.of(carrier.actionableError());
            }
            current = current.getCause();
        }
        return Optional.empty();
    }
}
