package sh.zolt.cli.surface;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.assertContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import org.junit.jupiter.api.Test;

final class CliSetupToolingHelpSectionSurfaceTest {
    @Test
    void initHelpPlacesProjectNameArgumentBeforeOptions() {
        CommandResult result = execute("init", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Create a new Zolt project.",
                "Usage:",
                "Arguments:",
                "NAME",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "--directory",
                "--group",
                "--java",
                "--no-tests",
                "--workspace");
        assertFalse(result.stdout().contains("Diagnostics:"));
        assertFalse(result.stdout().contains("Resolution:"));
    }

    @Test
    void initHelpColorsArgumentsSectionAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "init", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mArguments:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt init\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[36mNAME\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--group\u001B[0m\u001B[36m <GROUP>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--java\u001B[0m\u001B[36m <JAVA_VERSION>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--workspace\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--no-tests\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32mDiagnostics:\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32mResolution:\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void ideModelHelpGroupsWorkspaceOutputResolutionAndDiagnosticsOptions() {
        CommandResult result = execute("ide", "model", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Export the Zolt project model.",
                "Usage:",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "--check-lock",
                "--directory",
                "Workspace Selection:",
                "--workspace",
                "Output:",
                "--format",
                "Resolution:",
                "--offline",
                "Diagnostics:",
                "--timings",
                "--timings-format");
    }

    @Test
    void ideModelHelpColorsGroupedSectionsAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "ide", "model", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mWorkspace Selection:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOutput:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mResolution:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mDiagnostics:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt ide model\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--check-lock\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--workspace\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--format\u001B[0m\u001B[36m <FORMAT>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--offline\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--timings-format\u001B[0m\u001B[36m <FORMAT>\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }
}
