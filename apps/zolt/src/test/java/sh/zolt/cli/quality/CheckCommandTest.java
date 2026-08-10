package sh.zolt.cli.quality;

import sh.zolt.cli.WorkspaceCommandFixture;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CheckCommandTest extends CheckCommandTestSupport {

    @Test
    void checkSucceedsForTypedProjectModel() throws IOException {
        Path projectDir = createProject("check-demo");

        CommandResult result = execute("check", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertEquals(
                """
                Checking project
                ok command-surface check-demo zolt check uses typed Zolt project data; no Maven, Gradle, or shell hooks are run.
                Check status: ok
                ok: Checked 1 quality checks: 1 passed, 0 warnings, 0 failed, 0 skipped
                """,
                result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void checkAcceptsVisibleProjectDirectoryOption() throws IOException {
        Path projectDir = createProject("check-directory");

        CommandResult result = execute("check", "--directory", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Checking project"));
        assertTrue(result.stdout().contains("ok command-surface check-directory"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkColorsOnlyHumanFrameAndStatusMarkersWhenForced() throws IOException {
        Path projectDir = createProject("check-color");

        CommandResult result = execute("--color=always", "check", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\u001B[36mChecking\u001B[0m project"));
        assertTrue(result.stdout().contains("\u001B[32mok\u001B[0m command-surface check-color"));
        assertTrue(result.stdout().contains("Check status: \u001B[32mok\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[32mok:\u001B[0m Checked 1 quality checks: 1 passed, 0 warnings"));
        assertFalse(result.stdout().contains("\u001B[36mChecking project"));
        assertFalse(result.stdout().contains("\u001B[32mok command-surface check-color\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[32mok: Checked 1 quality checks: 1 passed, 0 warnings"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkJsonOutputUsesStableResultShape() throws IOException {
        Path projectDir = createProject("check-json");

        CommandResult result = execute("check", "--format", "json", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().startsWith(
                "{\"schemaVersion\":1,\"command\":\"check\",\"status\":\"ok\",\"projectRoot\":\""));
        assertTrue(result.stdout().contains("\"diagnostics\":[]"));
        assertTrue(result.stdout().contains("\"workspace\":false"));
        assertTrue(result.stdout().contains("\"id\":\"command-surface\""));
        assertTrue(result.stdout().contains("\"severity\":\"info\""));
        assertTrue(result.stdout().contains("\"status\":\"passed\""));
        assertTrue(result.stdout().contains("\"subject\":\"check-json\""));
        assertTrue(result.stdout().endsWith("]}\n"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkRefusesArbitraryHookNames() throws IOException {
        Path projectDir = createProject("check-hook");

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "mvn verify");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error unsupported-check mvn verify Unsupported quality check `mvn verify`."));
        assertTrue(result.stdout().contains("Zolt does not run Maven goals, Gradle tasks, shell commands, or arbitrary hooks."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkWarnsWhenLegacyBuildFilesShareDefaultTargetOutputRoot() throws IOException {
        Path projectDir = createProject("check-output-root-migration");
        Files.writeString(projectDir.resolve("pom.xml"), "<project></project>\n");

        CommandResult result = execute("check", "--check", "project-model", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok project-model check-output-root-migration Project model is valid"));
        assertTrue(result.stdout().contains("warning project-model [build].outputRoot Maven or Gradle project files are present (pom.xml) while Zolt outputRoot is `target`"));
        assertTrue(result.stdout().contains("next: For side-by-side migration, set [build].outputRoot = \".zolt/build\""));
        assertEquals("", result.stderr());
    }

    @Test
    void checkLeavesNextStepLabelsPlainWhenWarningIsColored() throws IOException {
        Path projectDir = createProject("check-next-step-color");
        Files.writeString(projectDir.resolve("pom.xml"), "<project></project>\n");

        CommandResult result = execute(
                "--color=always", "check", "--check", "project-model", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\u001B[33mwarning\u001B[0m project-model [build].outputRoot"));
        assertEquals(
                "  next: For side-by-side migration, set [build].outputRoot = \".zolt/build\" in zolt.toml so Zolt-owned outputs stay separate.",
                lineContaining(result.stdout(), "next: For side-by-side migration"));
        assertFalse(result.stdout().contains("\u001B[33mwarning project-model"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkDoesNotWarnWhenMigrationOutputRootIsIsolated() throws IOException {
        Path projectDir = createProject("check-output-root-isolated");
        Files.writeString(projectDir.resolve("pom.xml"), "<project></project>\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-output-root-isolated")
                + """

                [build]
                outputRoot = ".zolt/build"
                """);

        CommandResult result = execute("check", "--check", "project-model", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok project-model check-output-root-isolated Project model is valid"));
        assertTrue(!result.stdout().contains("warning project-model [build].outputRoot"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkReportsMalformedConfigAsFailedCheck() throws IOException {
        Path projectDir = createProject("check-malformed");
        Files.writeString(projectDir.resolve("zolt.toml"), """

                [check]
                command = "mvn verify"
                """);

        CommandResult result = execute("check", "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error command-surface zolt.toml Unknown top-level section [check] in zolt.toml."));
        assertTrue(result.stdout().contains("next: Fix zolt.toml, then run `zolt check` again."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkWorkspaceUsesMemberSelectionModel() throws IOException {
        WorkspaceCommandFixture.WorkspaceApplicationFixture fixture =
                WorkspaceCommandFixture.create(tempDir, "check-workspace");

        CommandResult result = execute(
                "check",
                "--workspace",
                "--member", "apps/api",
                "--cwd", fixture.workspaceDir().toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok command-surface check-workspace zolt check selected 2 workspace members"));
        assertTrue(result.stdout().contains("no Maven, Gradle, or shell hooks are run."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkPrintsTimingsWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("check-timings");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-timings"));

        CommandResult result = execute("check", "--timings", "--timings-format", "json", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok command-surface check-timings"));
        assertTrue(result.stderr().contains("\"command\":\"check\""));
        assertTrue(result.stderr().contains("\"phase\":\"run quality checks\""));
        assertTrue(result.stderr().contains("\"checks\":\"1\""));
        assertTrue(result.stderr().contains("\"passed\":\"1\""));
    }

    private static String lineContaining(String text, String fragment) {
        for (String line : text.split("\\R")) {
            if (line.contains(fragment)) {
                return line;
            }
        }
        return "";
    }

}
