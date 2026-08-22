package sh.zolt.cli;

import sh.zolt.cli.console.ConsoleStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Help;
import picocli.CommandLine.IHelpSectionRenderer;
import picocli.CommandLine.Model.CommandSpec;

final class RootCommandListRenderer implements IHelpSectionRenderer {
    private static final int COMMAND_COLUMN_WIDTH = 20;
    private static final String HELP_COMMAND = "zolt help <command>";

    /**
     * The public command surface, in the order end users see it. Zolt's own release and self-hosting
     * machinery ({@code self-check}, {@code self-parity}, {@code native-smoke}, {@code release-archive},
     * {@code release-index}, {@code release-verify}) is declared {@code hidden = true} and stays out of
     * this table; the hidden filter below also keeps it out of the trailing "Other" group. Those commands
     * remain fully invokable, and {@code zolt help <command>} still renders their usage.
     */
    private static final List<CommandGroup> GROUPS = List.of(
            new CommandGroup("Basics", List.of(
                    "help",
                    "init",
                    "version",
                    "config",
                    "doctor",
                    "self")),
            new CommandGroup("Dependencies", List.of(
                    "add",
                    "remove",
                    "versions",
                    "platforms",
                    "bom",
                    "resolve",
                    "tree",
                    "why",
                    "policy",
                    "conflicts",
                    "outdated",
                    "update")),
            new CommandGroup("Build, Test, Run", List.of(
                    "aliases",
                    "tasks",
                    "task",
                    "build",
                    "run",
                    "exec",
                    "test",
                    "integration-test",
                    "coverage",
                    "package",
                    "run-package",
                    "clean")),
            new CommandGroup("Insight and Tooling", List.of(
                    "check",
                    "plan",
                    "classpath",
                    "ide",
                    "toolchain",
                    "shims",
                    "explain",
                    "quarkus")),
            new CommandGroup("Native and Release", List.of(
                    "native",
                    "publish")),
            new CommandGroup("Supply Chain", List.of(
                    "sbom",
                    "licenses")));

    private final Supplier<ConsoleStyle> styles;

    RootCommandListRenderer(Supplier<ConsoleStyle> styles) {
        this.styles = styles;
    }

    static String render(CommandLine commandLine, ConsoleStyle style) {
        Map<String, CommandSpec> subcommands = new LinkedHashMap<>();
        commandLine.getSubcommands().forEach((name, subcommand) -> subcommands.put(name, subcommand.getCommandSpec()));
        return render(subcommands, style);
    }

    @Override
    public String render(Help help) {
        Map<String, CommandSpec> subcommands = new LinkedHashMap<>();
        help.subcommands().forEach((name, commandHelp) -> subcommands.put(name, commandHelp.commandSpec()));
        if ("zolt".equals(help.commandSpec().name())) {
            return render(subcommands, styles.get());
        }
        return renderUngrouped(subcommands, styles.get());
    }

    private static String render(Map<String, CommandSpec> subcommands, ConsoleStyle style) {
        if (subcommands.isEmpty()) {
            return "";
        }

        StringBuilder output = new StringBuilder();
        Set<String> rendered = new LinkedHashSet<>();
        for (CommandGroup group : GROUPS) {
            appendGroup(output, group, subcommands, rendered, style);
        }
        appendUngroupedCommands(output, subcommands, rendered, style);
        appendHelpFooter(output, style);
        return output.toString();
    }

    private static void appendGroup(
            StringBuilder output,
            CommandGroup group,
            Map<String, CommandSpec> subcommands,
            Set<String> rendered,
            ConsoleStyle style) {
        List<String> visibleCommands = new ArrayList<>();
        for (String command : group.commands()) {
            CommandSpec commandSpec = subcommands.get(command);
            if (commandSpec != null && !commandSpec.usageMessage().hidden()) {
                visibleCommands.add(command);
            }
        }
        if (visibleCommands.isEmpty()) {
            return;
        }

        appendHeading(output, group.heading(), style);
        for (String command : visibleCommands) {
            appendCommand(output, command, subcommands.get(command), style);
            rendered.add(command);
        }
        output.append(System.lineSeparator());
    }

    private static void appendUngroupedCommands(
            StringBuilder output,
            Map<String, CommandSpec> subcommands,
            Set<String> rendered,
            ConsoleStyle style) {
        List<String> ungrouped = new ArrayList<>();
        for (Map.Entry<String, CommandSpec> entry : subcommands.entrySet()) {
            if (!rendered.contains(entry.getKey())
                    && !entry.getValue().usageMessage().hidden()) {
                ungrouped.add(entry.getKey());
            }
        }
        if (ungrouped.isEmpty()) {
            return;
        }

        appendHeading(output, "Other", style);
        for (String command : ungrouped) {
            appendCommand(output, command, subcommands.get(command), style);
        }
        output.append(System.lineSeparator());
    }

    private static String renderUngrouped(Map<String, CommandSpec> subcommands, ConsoleStyle style) {
        StringBuilder output = new StringBuilder();
        for (Map.Entry<String, CommandSpec> entry : subcommands.entrySet()) {
            if (!entry.getValue().usageMessage().hidden()) {
                appendCommand(output, entry.getKey(), entry.getValue(), style);
            }
        }
        return output.toString();
    }

    private static void appendHeading(StringBuilder output, String heading, ConsoleStyle style) {
        output.append("  ")
                .append(style.helpHeading(heading))
                .append(System.lineSeparator());
    }

    private static void appendCommand(StringBuilder output, String command, CommandSpec commandSpec, ConsoleStyle style) {
        output.append("    ")
                .append(style.helpCommand(command))
                .append(padding(command))
                .append(description(commandSpec))
                .append(System.lineSeparator());
    }

    private static void appendHelpFooter(StringBuilder output, ConsoleStyle style) {
        output.append("See '")
                .append(style.helpCommand("zolt help"))
                .append(style.helpMeta(" <command>"))
                .append("' for more information on a specific command.")
                .append(System.lineSeparator());
    }

    private static String padding(String command) {
        if (command.length() >= COMMAND_COLUMN_WIDTH) {
            return "  ";
        }
        return " ".repeat(COMMAND_COLUMN_WIDTH - command.length());
    }

    private static String description(CommandSpec commandSpec) {
        if ("help".equals(commandSpec.name())) {
            return "Display help for zolt or a command.";
        }

        StringBuilder description = new StringBuilder();
        for (String line : commandSpec.usageMessage().description()) {
            String trimmed = line.replace("%n", " ").trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!description.isEmpty()) {
                description.append(' ');
            }
            description.append(trimmed);
        }
        return description.toString();
    }

    private record CommandGroup(String heading, List<String> commands) {
    }
}
