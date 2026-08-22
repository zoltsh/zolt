package sh.zolt.cli.surface;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.assertContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import org.junit.jupiter.api.Test;

final class CliCommandHelpSectionSurfaceTest {
    @Test
    void buildHelpGroupsWorkspaceResolutionAndDiagnosticsOptions() {
        CommandResult result = execute("build", "--help");

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
                "Resolution:",
                "--offline",
                "Diagnostics:",
                "--timings",
                "--timings-format");
    }

    @Test
    void buildHelpColorsSectionHeadingsAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "build", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mWorkspace Selection:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mResolution:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mDiagnostics:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt build\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--workspace\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--offline\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--timings-format\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void packageHelpGroupsWorkspaceOutputAndDiagnosticsOptions() {
        CommandResult result = execute("package", "--help");

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
                "--mode",
                "--plan",
                "Workspace Selection:",
                "--workspace",
                "--all",
                "--member",
                "--members",
                "Output:",
                "--format",
                "Diagnostics:",
                "--timings",
                "--timings-format");
    }

    @Test
    void packageHelpColorsSectionHeadingsAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "package", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mWorkspace Selection:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOutput:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mDiagnostics:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt package\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--mode\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--workspace\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--format\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--timings-format\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void runHelpGroupsArgumentsWorkspaceAndDiagnosticsOptions() {
        CommandResult result = execute("run", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Build and run the configured main class.",
                "Usage:",
                "Arguments:",
                "[ARGS...]",
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
                "Diagnostics:",
                "--timings",
                "--timings-format");
    }

    @Test
    void runHelpColorsArgumentsSectionHeadingsAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "run", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mArguments:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mWorkspace Selection:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mDiagnostics:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt run\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[36m[ARGS...]\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--workspace\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--timings-format\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void runPackageHelpGroupsArgumentsWorkspaceAndDiagnosticsOptions() {
        CommandResult result = execute("run-package", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["));
        assertContainsInOrder(
                result.stdout(),
                "Run a packaged jar with runtime dependencies.",
                "Usage:",
                "Arguments:",
                "[ARGS...]",
                "Options:",
                "--color",
                "--progress",
                "--no-progress",
                "--quiet",
                "--help",
                "--version",
                "--directory",
                "--mode",
                "Workspace Selection:",
                "--workspace",
                "--all",
                "--member",
                "--members",
                "Diagnostics:",
                "--timings",
                "--timings-format");
    }

    @Test
    void runPackageHelpColorsArgumentsSectionHeadingsAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "run-package", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mArguments:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mWorkspace Selection:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mDiagnostics:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt run-package\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[36m[ARGS...]\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--mode\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--workspace\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--timings-format\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void nativeHelpGroupsNativeImageAndWorkspaceOptions() {
        CommandResult result = execute("native", "--help");

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
                "--native-image",
                "Workspace Selection:",
                "--workspace",
                "--all",
                "--member",
                "--members");
    }

    @Test
    void nativeHelpColorsSectionHeadingsAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "native", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mWorkspace Selection:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt native\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--native-image\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--workspace\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }

    @Test
    void resolveHelpGroupsWorkspaceResolutionAndDiagnosticsOptions() {
        CommandResult result = execute("resolve", "--help");

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
                "Resolution:",
                "--offline",
                "--locked",
                "--repository-overlay",
                "--no-local-overlays",
                "Diagnostics:",
                "--timings",
                "--timings-format");
    }

    @Test
    void resolveHelpColorsSectionHeadingsAndOptionsWithoutWarningColor() {
        CommandResult result = execute("--color=always", "resolve", "--help");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\u001B[1;32mOptions:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mWorkspace Selection:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mResolution:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;32mDiagnostics:\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36mzolt resolve\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--directory\u001B[0m\u001B[36m <DIRECTORY>\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--workspace\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--repository-overlay\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--no-local-overlays\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[1;36m--timings-format\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[1;32m--"));
        assertFalse(result.stdout().contains("\u001B[33m"));
    }
}
