package sh.zolt.cli.surface;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.assertContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import org.junit.jupiter.api.Test;

final class CliInsightHelpSectionSurfaceTest {
    @Test
    void treeHelpGroupsOptionsAndOutput() {
        CommandResult result = execute("tree", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Display the resolved dependency graph.",
                "Usage:",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "--directory",
                "Workspace Selection:",
                "--workspace",
                "Output:",
                "--format");
    }

    @Test
    void treeHelpColorsSectionsAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "tree", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mWorkspace Selection:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOutput:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt tree\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--workspace\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--format\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void whyHelpGroupsArgumentsOptionsAndOutput() {
        CommandResult result = execute("why", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Explain why a package is present.",
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
                "Output:",
                "--format");
    }

    @Test
    void whyHelpColorsSectionsArgumentsAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "why", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mArguments:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOutput:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt why\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[36mGROUP:ARTIFACT\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--format\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void classpathHelpGroupsArgumentsOptionsAndOutput() {
        CommandResult result = execute("classpath", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Print a classpath from zolt.lock.",
                "Usage:",
                "Arguments:",
                "compile|runtime|test|processor|test-processor|quarkus-deployment|audit",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "--directory",
                "Output:",
                "--format");
    }

    @Test
    void classpathHelpColorsSectionsArgumentsAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "classpath", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mArguments:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOutput:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt classpath\u001B[0m"));
        assertTrue(result.stdout().contains("compile|runtime|test|processor|test-processor|quarkus-deployment|audit"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--format\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void conflictsHelpGroupsOptionsOnly() {
        CommandResult result = execute("conflicts", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Show version conflicts and selected versions.",
                "Usage:",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "--directory");
        assertFalse(result.stdout().contains("Arguments:"));
        assertFalse(result.stdout().contains("Output:"));
    }

    @Test
    void conflictsHelpColorsOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "conflicts", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt conflicts\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32mArguments:\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32mOutput:\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }
}
