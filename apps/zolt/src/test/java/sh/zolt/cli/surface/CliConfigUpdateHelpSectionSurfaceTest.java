package sh.zolt.cli.surface;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.assertContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import org.junit.jupiter.api.Test;

final class CliConfigUpdateHelpSectionSurfaceTest {
    @Test
    void configHelpShowsOptionsBeforeConfigCommands() {
        CommandResult result = execute("config", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Inspect user-local Zolt config diagnostics.",
                "Usage:",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "Commands:",
                "show");
    }

    @Test
    void configHelpColorsOptionsAndCommandListWithoutWarningColor() {
        CommandResult result = execute("--color=always", "config", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mCommands:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt config\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--help\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mshow\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void configShowHelpKeepsConfigPathOptionWithDefaults() {
        CommandResult result = execute("config", "show", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Show effective user global config settings.",
                "Usage:",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "--config",
                "Defaults to ~/.zolt/config");
        assertFalse(result.stdout().contains("Diagnostics:"));
        assertFalse(result.stdout().contains("Resolution:"));
    }

    @Test
    void configShowHelpColorsConfigOptionWithoutWarningColor() {
        CommandResult result = execute("--color=always", "config", "show", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt config show\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--config\u001B[0m\u001B[36m <CONFIG_PATH>\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32mDiagnostics:\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32mResolution:\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void updateHelpKeepsDefaultOptionsOnly() {
        CommandResult result = execute("update", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Update dependency, platform, and version-alias versions in zolt.toml.",
                "Usage:",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version");
        assertTrue(result.stdout().contains("--target-id"));
        assertTrue(result.stdout().contains("--to"));
        assertTrue(result.stdout().contains("--schema-version"));
        assertFalse(result.stdout().contains("Commands:"));
        assertFalse(result.stdout().contains("Diagnostics:"));
    }

    @Test
    void updateHelpColorsOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "update", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt update\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--help\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--target-id\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--to\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--schema-version\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32mCommands:\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32mDiagnostics:\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }
}
