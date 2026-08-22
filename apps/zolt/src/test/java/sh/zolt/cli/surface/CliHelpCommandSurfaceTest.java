package sh.zolt.cli.surface;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.newCommandLine;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.ANSI_ESCAPE;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.BOLD_CYAN_HELP_OPTION;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.BOLD_COMMANDS_HEADING;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.BOLD_GREEN_OPTION;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.BOLD_USAGE_HEADING;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.WARNING_COLOR;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.assertContainsInOrder;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.commandPaths;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.directColorNeverHelp;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.helpCommandColorNever;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.topLevelCommandNames;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CliHelpCommandSurfaceTest {
    @Test
    void helpCommandHelpPlacesCommandPathArgumentBeforeOptions() {
        CommandResult result = execute("help", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains(ANSI_ESCAPE));
        assertContainsInOrder(
                result.stdout(),
                "Display help for zolt or a command.",
                "Usage:",
                "Arguments:",
                "[COMMAND...]",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version");
    }

    @Test
    void helpCommandHelpColorsArgumentsSectionAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "help", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mArguments:\u001B[0m"));
        assertTrue(result.stdout().contains("Command path to show help for."));
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt help\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[36m[COMMAND...]\u001B[0m"));
        assertTrue(result.stdout().contains(BOLD_CYAN_HELP_OPTION));
        assertFalse(result.stdout().contains(BOLD_GREEN_OPTION));
        assertFalse(result.stdout().contains(WARNING_COLOR));
    }

    @Test
    void helpCommandRespectsColorModesForTopLevelCommands() {
        for (String command : topLevelCommandNames(newCommandLine())) {
            CommandResult colored = execute("--color=always", "help", command);
            assertEquals(0, colored.exitCode(), "zolt help " + command + " should exit successfully");
            assertEquals("", colored.stderr(), "zolt help " + command + " should not write stderr");
            assertTrue(
                    colored.stdout().contains(BOLD_USAGE_HEADING),
                    "zolt help " + command + " should use a bold green usage heading");
            assertTrue(
                    colored.stdout().contains(BOLD_CYAN_HELP_OPTION),
                    "zolt help " + command + " should use bold cyan option tokens");
            assertFalse(
                    colored.stdout().contains(BOLD_GREEN_OPTION),
                    "zolt help " + command + " should not use green option tokens");
            assertFalse(
                    colored.stdout().contains(WARNING_COLOR),
                    "zolt help " + command + " should not use warning color");

            CommandResult plain = execute("--color=never", "help", command);
            assertEquals(0, plain.exitCode(), "zolt help " + command + " --color=never should exit successfully");
            assertEquals("", plain.stderr(), "zolt help " + command + " --color=never should not write stderr");
            assertFalse(plain.stdout().contains(ANSI_ESCAPE), "zolt help " + command + " should not color stdout");
            assertFalse(plain.stderr().contains(ANSI_ESCAPE), "zolt help " + command + " should not color stderr");
        }
    }

    @Test
    void helpCommandResolvesNestedCommandPaths() {
        CommandResult colored = execute("--color=always", "help", "versions", "set");

        assertEquals(0, colored.exitCode());
        assertEquals("", colored.stderr());
        assertTrue(colored.stdout().contains(BOLD_USAGE_HEADING + " \u001B[1;36mzolt versions set\u001B[0m"));
        assertTrue(colored.stdout().contains("\u001B[36mALIAS\u001B[0m"));
        assertTrue(colored.stdout().contains("\u001B[36mVERSION\u001B[0m"));
        assertTrue(colored.stdout().contains(BOLD_CYAN_HELP_OPTION));
        assertFalse(colored.stdout().contains(BOLD_COMMANDS_HEADING));
        assertFalse(colored.stdout().contains(WARNING_COLOR));

        CommandResult plain = execute("--color=never", "help", "versions", "set");

        assertEquals(0, plain.exitCode());
        assertEquals("", plain.stderr());
        assertTrue(plain.stdout().contains("Usage: zolt versions set"));
        assertTrue(plain.stdout().contains("ALIAS"));
        assertTrue(plain.stdout().contains("VERSION"));
        assertFalse(plain.stdout().contains("Commands:"));
        assertFalse(plain.stdout().contains(ANSI_ESCAPE));
    }

    @Test
    void helpCommandShowsNearestCommandUsageForUnknownNestedCommand() {
        CommandResult result = execute("--color=never", "help", "versions", "nope");

        assertEquals(2, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().startsWith("error: Unknown subcommand 'nope' under 'zolt versions'."));
        assertTrue(result.stderr().contains("Usage: zolt versions"));
        assertTrue(result.stderr().contains("Commands:"));
        assertTrue(result.stderr().contains("    set"));
        assertFalse(result.stderr().contains("  Dependencies"));
        assertFalse(result.stderr().contains(ANSI_ESCAPE));
    }

    @Test
    void helpCommandShowsRootUsageForUnknownTopLevelCommand() {
        CommandResult result = execute("--color=never", "help", "nope");

        assertEquals(2, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().startsWith("error: Unknown subcommand 'nope' under 'zolt'."));
        assertTrue(result.stderr().contains("Usage: zolt"));
        assertTrue(result.stderr().contains("  Basics"));
        assertTrue(result.stderr().contains("    version"));
        assertTrue(result.stderr().contains("  Dependencies"));
        assertFalse(result.stderr().contains(ANSI_ESCAPE));
    }

    @Test
    void helpCommandStylesUnknownCommandUsageWhenColorIsForced() {
        CommandResult topLevel = execute("--color=always", "help", "nope");

        assertEquals(2, topLevel.exitCode());
        assertEquals("", topLevel.stdout());
        assertTrue(topLevel.stderr().startsWith("\u001B[31merror:\u001B[0m Unknown subcommand 'nope' under 'zolt'."));
        assertTrue(topLevel.stderr().contains(BOLD_USAGE_HEADING + " \u001B[1;36mzolt\u001B[0m"));
        assertTrue(topLevel.stderr().contains(BOLD_COMMANDS_HEADING));
        assertTrue(topLevel.stderr().contains(BOLD_CYAN_HELP_OPTION));
        assertFalse(topLevel.stderr().contains("\u001B[31merror: Unknown"));
        assertFalse(topLevel.stderr().contains(BOLD_GREEN_OPTION));
        assertFalse(topLevel.stderr().contains(WARNING_COLOR));

        CommandResult nested = execute("--color=always", "help", "versions", "nope");

        assertEquals(2, nested.exitCode());
        assertEquals("", nested.stdout());
        assertTrue(nested.stderr().startsWith(
                "\u001B[31merror:\u001B[0m Unknown subcommand 'nope' under 'zolt versions'."));
        assertTrue(nested.stderr().contains(BOLD_USAGE_HEADING + " \u001B[1;36mzolt versions\u001B[0m"));
        assertTrue(nested.stderr().contains(BOLD_COMMANDS_HEADING));
        assertTrue(nested.stderr().contains(BOLD_CYAN_HELP_OPTION));
        assertFalse(nested.stderr().contains("\u001B[31merror: Unknown"));
        assertFalse(nested.stderr().contains(BOLD_GREEN_OPTION));
        assertFalse(nested.stderr().contains(WARNING_COLOR));
    }

    @Test
    void helpCommandMatchesDirectHelpForRegisteredCommandPaths() {
        for (List<String> path : commandPaths(newCommandLine())) {
            CommandResult direct = directColorNeverHelp(path);
            CommandResult viaHelp = helpCommandColorNever(path);

            String commandName = path.isEmpty() ? "zolt" : "zolt " + String.join(" ", path);
            assertEquals(0, direct.exitCode(), commandName + " direct help should exit successfully");
            assertEquals(0, viaHelp.exitCode(), commandName + " help command should exit successfully");
            assertEquals("", direct.stderr(), commandName + " direct help should not write stderr");
            assertEquals("", viaHelp.stderr(), commandName + " help command should not write stderr");
            assertEquals(direct.stdout(), viaHelp.stdout(), commandName + " help output should match");
        }
    }
}
