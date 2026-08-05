package sh.zolt.cli.surface;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.assertContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import org.junit.jupiter.api.Test;

final class CliTestFamilyHelpSectionSurfaceTest {
    @Test
    void testHelpGroupsWorkspaceSelectionRuntimeOutputAndDiagnosticsOptions() {
        CommandResult result = execute("test", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Compile and run tests, starting with JUnit support.",
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
                "--all",
                "--member",
                "--members",
                "Test Selection:",
                "--test",
                "--tests",
                "--include-tag",
                "--exclude-tag",
                "Test Runtime:",
                "--jvm-arg",
                "--test-workers",
                "Output:",
                "--reports-dir",
                "--test-event",
                "Diagnostics:",
                "--profile-tests",
                "--profile-dir",
                "--profile-top",
                "--profile-min",
                "--timings",
                "--timings-format");
    }

    @Test
    void testHelpColorsSectionHeadingsAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "test", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mWorkspace Selection:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mTest Selection:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mTest Runtime:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOutput:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mDiagnostics:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt test\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--color\u001B[0m\u001B[36m <WHEN>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--workspace\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--test\u001B[0m\u001B[36m <TEST_SELECTORS>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--jvm-arg\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--test-workers\u001B[0m\u001B[36m <TEST_WORKERS>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--reports-dir\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--profile-tests\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--profile-dir\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--profile-top\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--profile-min\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--timings-format\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void testPlanHelpGroupsProjectAndSelectionOptions() {
        CommandResult result = execute("test", "plan", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Show the selected test suite plan without executing tests.",
                "Usage:",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "--directory",
                "Test Selection:",
                "--suite",
                "--shard-count",
                "--balance-from",
                "--test",
                "--tests",
                "--include-tag",
                "--exclude-tag",
                "Output:",
                "--format",
                "--reports-dir");
    }

    @Test
    void integrationTestHelpGroupsWorkspaceSelectionRuntimeOutputAndDiagnosticsOptions() {
        CommandResult result = execute("integration-test", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
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
                "--all",
                "--member",
                "--members",
                "Test Selection:",
                "--test",
                "--tests",
                "--include-tag",
                "--exclude-tag",
                "Test Runtime:",
                "--jvm-arg",
                "Output:",
                "--reports-dir",
                "--test-event",
                "Diagnostics:",
                "--timings",
                "--timings-format");
    }

    @Test
    void integrationTestHelpColorsSectionHeadingsAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "integration-test", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mWorkspace Selection:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mTest Selection:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mTest Runtime:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOutput:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mDiagnostics:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt integration-test\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--color\u001B[0m\u001B[36m <WHEN>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--workspace\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--test\u001B[0m\u001B[36m <TEST_SELECTORS>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--jvm-arg\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--reports-dir\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--timings-format\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void coverageHelpGroupsReportControlsUnderOutputOptions() {
        CommandResult result = execute("coverage", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
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
                "--all",
                "--member",
                "--members",
                "Test Selection:",
                "--suite",
                "--shard",
                "--test",
                "--tests",
                "--include-tag",
                "--exclude-tag",
                "Output:",
                "--reports-dir",
                "--test-event",
                "--no-xml",
                "--no-html",
                "--exec-file",
                "--xml-report",
                "--html-dir",
                "Diagnostics:",
                "--timings",
                "--timings-format");
    }

    @Test
    void coverageHelpColorsOutputSectionAndReportOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "coverage", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mWorkspace Selection:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mTest Selection:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOutput:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mDiagnostics:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt coverage\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--workspace\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--suite\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--shard\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--test\u001B[0m\u001B[36m <TEST_SELECTORS>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--no-xml\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--xml-report\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--html-dir\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--timings-format\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }
}
