package sh.zolt.cli.surface;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.assertContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import org.junit.jupiter.api.Test;

final class CliCommandGroupHelpSectionSurfaceTest {
    @Test
    void versionsHelpShowsOptionsBeforeVersionCommands() {
        CommandResult result = execute("versions", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Manage version aliases in [versions].",
                "Usage:",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "Commands:",
                "set",
                "remove");
        assertFalse(result.stdout().contains("  Dependencies"));
    }

    @Test
    void versionsHelpColorsOptionsAndCommandListWithoutWarningColor() {
        CommandResult result = execute("--color=always", "versions", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mCommands:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt versions\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mset\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mremove\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void platformsHelpShowsOptionsBeforePlatformCommands() {
        CommandResult result = execute("platforms", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Manage imported platforms in [platforms].",
                "Usage:",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "Commands:",
                "set",
                "remove");
        assertFalse(result.stdout().contains("  Dependencies"));
    }

    @Test
    void platformsHelpColorsOptionsAndCommandListWithoutWarningColor() {
        CommandResult result = execute("--color=always", "platforms", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mCommands:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt platforms\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mset\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mremove\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void ideHelpShowsOptionsBeforeIdeCommands() {
        CommandResult result = execute("ide", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Export project models for IDE and tooling integrations.",
                "Usage:",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "Commands:",
                "model");
        assertFalse(result.stdout().contains("Diagnostics:"));
    }

    @Test
    void ideHelpColorsOptionsAndCommandListWithoutWarningColor() {
        CommandResult result = execute("--color=always", "ide", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mCommands:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt ide\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mmodel\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32mDiagnostics:\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void quarkusHelpShowsOptionsBeforeQuarkusCommands() {
        CommandResult result = execute("quarkus", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Inspect Quarkus build-time augmentation inputs.",
                "Usage:",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "Commands:",
                "plan",
                "test-plan");
        assertFalse(result.stdout().contains("Diagnostics:"));
    }

    @Test
    void quarkusHelpColorsOptionsAndCommandListWithoutWarningColor() {
        CommandResult result = execute("--color=always", "quarkus", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mCommands:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt quarkus\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mplan\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mtest-plan\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32mDiagnostics:\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }
}
