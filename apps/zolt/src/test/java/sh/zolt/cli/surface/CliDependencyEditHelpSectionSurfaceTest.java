package sh.zolt.cli.surface;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.assertContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import org.junit.jupiter.api.Test;

final class CliDependencyEditHelpSectionSurfaceTest {
    @Test
    void addHelpGroupsArgumentsAndResolutionOptions() {
        CommandResult result = execute("add", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Add a dependency to zolt.toml and refresh zolt.lock.",
                "Usage:",
                "Arguments:",
                "GROUP:ARTIFACT[:VERSION]",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "--directory",
                "--managed",
                "--version-ref",
                "Resolution:",
                "--no-resolve");
    }

    @Test
    void addHelpColorsArgumentsResolutionAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "add", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mArguments:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mResolution:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt add\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[36mGROUP:ARTIFACT[:VERSION]\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--managed\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--version-ref\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--no-resolve\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void removeHelpGroupsArgumentsAndOptions() {
        CommandResult result = execute("remove", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Remove a dependency and prune unused transitive packages.",
                "Usage:",
                "Arguments:",
                "GROUP:ARTIFACT",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "--directory",
                "Resolution:",
                "--no-resolve");
    }

    @Test
    void removeHelpColorsArgumentsAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "remove", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mArguments:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt remove\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[36mGROUP:ARTIFACT\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mResolution:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--no-resolve\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void versionsSetHelpGroupsArgumentsAndResolutionOptions() {
        CommandResult result = execute("versions", "set", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Set a version alias in zolt.toml and refresh zolt.lock.",
                "Usage:",
                "Arguments:",
                "ALIAS",
                "VERSION",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "--directory",
                "Resolution:",
                "--no-resolve");
    }

    @Test
    void versionsSetHelpColorsArgumentsResolutionAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "versions", "set", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mArguments:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mResolution:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt versions set\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[36mALIAS\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[36mVERSION\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--no-resolve\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void versionsRemoveHelpGroupsArgumentsAndResolutionOptions() {
        CommandResult result = execute("versions", "remove", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Remove an unused version alias from zolt.toml and refresh zolt.lock.",
                "Usage:",
                "Arguments:",
                "ALIAS",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "--directory",
                "Resolution:",
                "--no-resolve");
    }

    @Test
    void versionsRemoveHelpColorsArgumentsResolutionAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "versions", "remove", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mArguments:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mResolution:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt versions remove\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[36mALIAS\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--no-resolve\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void platformsSetHelpGroupsArgumentsAndResolutionOptions() {
        CommandResult result = execute("platforms", "set", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Set a platform in zolt.toml and refresh zolt.lock.",
                "Usage:",
                "Arguments:",
                "GROUP:ARTIFACT",
                "VERSION",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "--directory",
                "--version-ref",
                "Resolution:",
                "--no-resolve");
    }

    @Test
    void platformsSetHelpColorsArgumentsResolutionAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "platforms", "set", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mArguments:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mResolution:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt platforms set\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[36mGROUP:ARTIFACT\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--version-ref\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--no-resolve\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void platformsRemoveHelpGroupsArgumentsAndOptions() {
        CommandResult result = execute("platforms", "remove", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Remove a platform and refresh zolt.lock.",
                "Usage:",
                "Arguments:",
                "GROUP:ARTIFACT",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "--directory",
                "Resolution:",
                "--no-resolve");
    }

    @Test
    void platformsRemoveHelpColorsArgumentsAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "platforms", "remove", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mArguments:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt platforms remove\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[36mGROUP:ARTIFACT\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mResolution:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--no-resolve\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }
}
