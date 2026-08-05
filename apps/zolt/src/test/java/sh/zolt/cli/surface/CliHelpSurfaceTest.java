package sh.zolt.cli.surface;

import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.ANSI_ESCAPE;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.BOLD_BASICS_HEADING;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.BOLD_CYAN_COLOR_OPTION;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.BOLD_CYAN_HELP_OPTION;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.BOLD_CYAN_INIT_COMMAND;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.BOLD_CYAN_ZOLT_COMMAND;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.BOLD_COMMANDS_HEADING;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.BOLD_GREEN_OPTION;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.BOLD_USAGE_HEADING;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.CYAN_COMMAND_ARGUMENT;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.HELP_COMMAND_FOOTER;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.HELP_COMMAND_HINT;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.PLAIN_GREEN_OPTION;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.WARNING_COLOR;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.commandPaths;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.zoltCommandTypes;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.newCommandLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

final class CliHelpSurfaceTest {
    /**
     * Zolt's own release and self-hosting machinery. These stay registered and invokable for CI and
     * the release scripts, but must never surface in end-user help.
     */
    private static final List<String> INTERNAL_COMMANDS = List.of(
            "self-check",
            "self-parity",
            "native-smoke",
            "release-archive",
            "release-index",
            "release-verify");

    @Test
    void helpListsMvpCommands() {
        CommandResult result = execute("help");

        assertEquals(0, result.exitCode());
        assertFalse(result.stdout().contains(ANSI_ESCAPE));
        assertTrue(result.stdout().contains("The modern Java build toolkit."));
        assertTrue(result.stdout().contains("--color"));
        assertTrue(result.stdout().contains("--progress"));
        assertTrue(result.stdout().contains("--no-progress"));
        assertTrue(result.stdout().contains("--quiet"));
        assertTrue(result.stdout().contains("--list"));
        assertContainsInOrder(
                result.stdout(),
                "The modern Java build toolkit.",
                "Usage:",
                "Commands:",
                "  Basics",
                "    init",
                "    config",
                "    doctor",
                "  Dependencies",
                "    resolve",
                "    conflicts",
                "  Build, Test, Run",
                "    build",
                "    integration-test",
                "  Insight and Tooling",
                "    check",
                "    quarkus",
                "  Native and Release",
                "    native",
                "    publish",
                "  Supply Chain",
                "    sbom",
                "    licenses",
                HELP_COMMAND_FOOTER);
        assertTrue(result.stdout().contains("help                Display help for zolt or a command."));
        assertFalse(result.stdout().contains("%n"));
    }

    @Test
    void helpOmitsInternalReleaseAndSelfHostingCommands() {
        CommandResult result = execute("help");

        assertEquals(0, result.exitCode());
        for (String internalCommand : INTERNAL_COMMANDS) {
            assertFalse(
                    result.stdout().contains(internalCommand),
                    internalCommand + " is internal machinery and must not appear in root help");
        }
        assertFalse(result.stdout().contains("Self-Hosting"), "the Self-Hosting category must be gone");
    }

    @Test
    void listOmitsInternalReleaseAndSelfHostingCommands() {
        CommandResult result = execute("--list");

        assertEquals(0, result.exitCode());
        for (String internalCommand : INTERNAL_COMMANDS) {
            assertFalse(
                    result.stdout().contains(internalCommand),
                    internalCommand + " is internal machinery and must not be listed");
        }
        assertFalse(result.stdout().contains("Self-Hosting"), "the Self-Hosting category must be gone");
    }

    @Test
    void internalCommandsStayRegisteredAndHidden() {
        CommandLine root = newCommandLine();

        for (String internalCommand : INTERNAL_COMMANDS) {
            CommandLine subcommand = root.getSubcommands().get(internalCommand);
            assertTrue(subcommand != null, internalCommand + " must stay registered for CI and release scripts");
            assertTrue(
                    subcommand.getCommandSpec().usageMessage().hidden(),
                    internalCommand + " must be hidden from the public help surface");
        }
    }

    @Test
    void hiddenInternalCommandsStillResolveThroughHelp() {
        for (String internalCommand : INTERNAL_COMMANDS) {
            CommandResult viaHelpCommand = execute("--color=never", "help", internalCommand);
            CommandResult viaHelpOption = execute("--color=never", internalCommand, "--help");

            assertEquals(0, viaHelpCommand.exitCode(), "zolt help " + internalCommand + " should succeed");
            assertEquals("", viaHelpCommand.stderr(), "zolt help " + internalCommand + " should not write stderr");
            assertTrue(
                    viaHelpCommand.stdout().contains("zolt " + internalCommand),
                    "zolt help " + internalCommand + " should render its usage");

            assertEquals(0, viaHelpOption.exitCode(), "zolt " + internalCommand + " --help should succeed");
            assertTrue(
                    viaHelpOption.stdout().contains("zolt " + internalCommand),
                    "zolt " + internalCommand + " --help should render its usage");
        }
    }

    @Test
    void helpSupportsSparseSemanticColor() {
        CommandResult result = execute("--color=always", "help");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(BOLD_USAGE_HEADING));
        assertTrue(result.stdout().contains(BOLD_CYAN_ZOLT_COMMAND));
        assertTrue(result.stdout().contains(CYAN_COMMAND_ARGUMENT));
        assertTrue(result.stdout().contains(BOLD_CYAN_COLOR_OPTION));
        assertTrue(result.stdout().contains("\u001B[1;36m--color\u001B[0m \u001B[36m<WHEN>]\u001B[0m"));
        assertTrue(result.stdout().contains(BOLD_COMMANDS_HEADING));
        assertTrue(result.stdout().contains(BOLD_BASICS_HEADING));
        assertTrue(result.stdout().contains("    " + BOLD_CYAN_INIT_COMMAND
                + "                Create a new Zolt project."));
        assertTrue(result.stdout().contains(
                "See '" + HELP_COMMAND_HINT + "' for more information on a specific command."));
        assertTrue(result.stdout().contains("Create a new Zolt project."));
        assertFalse(result.stdout().contains(BOLD_GREEN_OPTION));
        assertFalse(result.stdout().contains(WARNING_COLOR));
        assertFalse(result.stderr().contains(ANSI_ESCAPE));
    }

    @Test
    void colorNeverKeepsHelpAnsiFree() {
        CommandResult result = execute("--color=never", "help");

        assertEquals(0, result.exitCode());
        assertFalse(result.stdout().contains(ANSI_ESCAPE));
        assertTrue(result.stdout().contains("  Basics"));
        assertTrue(result.stdout().contains("    init                Create a new Zolt project."));
    }

    @Test
    void listShowsGroupedCommandInventoryWithoutUsage() {
        CommandResult result = execute("--list");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains(ANSI_ESCAPE));
        assertFalse(result.stdout().contains("Usage:"));
        assertContainsInOrder(
                result.stdout(),
                "Commands:",
                "  Basics",
                "    help",
                "    init",
                "  Dependencies",
                "    resolve",
                "  Build, Test, Run",
                "    build",
                "  Insight and Tooling",
                "    check",
                "  Native and Release",
                "    native",
                "  Supply Chain",
                "    sbom",
                HELP_COMMAND_FOOTER);
    }

    @Test
    void listSupportsSparseSemanticColor() {
        CommandResult result = execute("--color=always", "--list");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(BOLD_COMMANDS_HEADING));
        assertTrue(result.stdout().contains(BOLD_BASICS_HEADING));
        assertTrue(result.stdout().contains(BOLD_CYAN_INIT_COMMAND));
        assertFalse(result.stderr().contains(ANSI_ESCAPE));
    }

    @Test
    void allRegisteredCommandsSupportDirectHelpOption() {
        for (List<String> path : commandPaths(newCommandLine())) {
            List<String> args = new ArrayList<>(path);
            args.add("--help");

            CommandResult result = execute(args.toArray(String[]::new));

            String commandName = path.isEmpty() ? "zolt" : "zolt " + String.join(" ", path);
            assertEquals(0, result.exitCode(), commandName + " --help should exit successfully");
            assertEquals("", result.stderr(), commandName + " --help should not write stderr");
            assertTrue(result.stdout().contains("Usage:"), commandName + " --help should print usage");
        }
    }

    @Test
    void allRegisteredCommandHelpRespectsColorNever() {
        for (List<String> path : commandPaths(newCommandLine())) {
            List<String> args = new ArrayList<>();
            args.add("--color=never");
            args.addAll(path);
            args.add("--help");

            CommandResult result = execute(args.toArray(String[]::new));

            String commandName = path.isEmpty() ? "zolt" : "zolt " + String.join(" ", path);
            assertEquals(0, result.exitCode(), commandName + " --help should exit successfully");
            assertEquals("", result.stderr(), commandName + " --help should not write stderr");
            assertFalse(result.stdout().contains(ANSI_ESCAPE), commandName + " --help should not color stdout");
            assertFalse(result.stderr().contains(ANSI_ESCAPE), commandName + " --help should not color stderr");
        }
    }

    @Test
    void allRegisteredCommandHelpUsesCargoStyleCyanOptionsWithoutWarningColor() {
        for (List<String> path : commandPaths(newCommandLine())) {
            List<String> args = new ArrayList<>();
            args.add("--color=always");
            args.addAll(path);
            args.add("--help");

            CommandResult result = execute(args.toArray(String[]::new));

            String commandName = path.isEmpty() ? "zolt" : "zolt " + String.join(" ", path);
            assertEquals(0, result.exitCode(), commandName + " --help should exit successfully");
            assertEquals("", result.stderr(), commandName + " --help should not write stderr");
            assertTrue(
                    result.stdout().contains(BOLD_USAGE_HEADING),
                    commandName + " --help should use a bold green usage heading");
            assertFalse(result.stdout().contains(WARNING_COLOR), commandName + " --help should not use warning color");
            assertFalse(result.stdout().contains(BOLD_GREEN_OPTION), commandName + " --help should not use green options");
            assertFalse(result.stdout().contains(PLAIN_GREEN_OPTION), commandName + " --help should not use plain green options");
            assertTrue(
                    result.stdout().contains(BOLD_CYAN_HELP_OPTION),
                    commandName + " --help should use bold cyan option tokens");
        }
    }

    @Test
    void directHelpIsConfiguredFromCompositionRoot() {
        for (Class<?> commandType : zoltCommandTypes(newCommandLine())) {
            CommandLine.Command annotation = commandType.getAnnotation(CommandLine.Command.class);
            assertFalse(
                    annotation.mixinStandardHelpOptions(),
                    commandType.getName() + " should rely on ZoltCli.configureUniversalHelp");
        }
    }

    @Test
    void leafCommandHelpDoesNotShowEmptyCommandList() {
        CommandResult result = execute("clean", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("Commands:"));
    }

    @Test
    void leafCommandForcedColorHelpDoesNotShowEmptyCommandList() {
        CommandResult result = execute("--color=always", "clean", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains(BOLD_COMMANDS_HEADING));
    }

    private static void assertContainsInOrder(String text, String... expected) {
        int previousIndex = -1;
        for (String item : expected) {
            int index = text.indexOf(item);
            assertTrue(index > previousIndex, "Expected `" + item + "` after index " + previousIndex);
            previousIndex = index;
        }
    }
}
