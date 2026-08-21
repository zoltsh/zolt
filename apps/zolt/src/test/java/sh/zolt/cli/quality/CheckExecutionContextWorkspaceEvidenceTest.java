package sh.zolt.cli.quality;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckExecutionContextWorkspaceEvidenceTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkContextCiAcceptsWorkspaceJUnitReportsWhenConfigured() throws IOException {
        Path workspaceDir = tempDir.resolve("check-context-ci-workspace-reports-ok");
        Path apiDir = workspaceDir.resolve("apps/api");
        Path reportsDir = apiDir.resolve("target/test-reports/apps/api");
        Files.createDirectories(reportsDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "check-context-ci-workspace-reports-ok"
                members = ["apps/api"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api"));
        Files.writeString(workspaceDir.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(reportsDir.resolve("TEST-api.xml"), "<testsuite tests=\"1\" failures=\"0\"/>\n");

        CommandResult result = execute(
                "check",
                "--workspace",
                "--member", "apps/api",
                "--context", "ci",
                "--check", "execution-context",
                "--reports-dir", "target/test-reports",
                "--cwd", workspaceDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok execution-context apps/api test-reports CI test report preflight found 1 JUnit XML report."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiRequiresWorkspaceJUnitReportsOnlyForSelectedMembers() throws IOException {
        Path workspaceDir = tempDir.resolve("check-context-ci-workspace-selected-reports-ok");
        Path coreDir = workspaceDir.resolve("modules/core");
        Path apiDir = workspaceDir.resolve("apps/api");
        Path reportsDir = apiDir.resolve("target/test-reports/apps/api");
        Files.createDirectories(coreDir);
        Files.createDirectories(reportsDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "check-context-ci-workspace-selected-reports-ok"
                members = ["modules/core", "apps/api"]
                """);
        Files.writeString(coreDir.resolve("zolt.toml"), memberConfig("core"));
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api") + """

                [dependencies]
                "com.example:core" = { workspace = "modules/core" }
                """);
        Files.writeString(workspaceDir.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(reportsDir.resolve("TEST-api.xml"), "<testsuite tests=\"1\" failures=\"0\"/>\n");

        CommandResult result = execute(
                "check",
                "--workspace",
                "--member", "apps/api",
                "--context", "ci",
                "--check", "execution-context",
                "--reports-dir", "target/test-reports",
                "--cwd", workspaceDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok execution-context apps/api test-reports CI test report preflight found 1 JUnit XML report."));
        assertTrue(result.stdout().contains("Checked 2 quality checks: 2 passed, 0 warnings, 0 failed, 0 skipped"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiAcceptsWorkspaceCoverageReportsWhenConfigured() throws IOException {
        Path workspaceDir = tempDir.resolve("check-context-ci-workspace-coverage-ok");
        Path apiDir = workspaceDir.resolve("apps/api");
        Path coverageDir = apiDir.resolve("target/coverage");
        Files.createDirectories(coverageDir.resolve("html"));
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "check-context-ci-workspace-coverage-ok"
                members = ["apps/api"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api"));
        Files.writeString(workspaceDir.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(coverageDir.resolve("jacoco.exec"), "exec\n");
        Files.writeString(coverageDir.resolve("jacoco.xml"), "<report name=\"api\"/>\n");
        Files.writeString(coverageDir.resolve("html/index.html"), "<!doctype html>\n");

        CommandResult result = execute(
                "check",
                "--workspace",
                "--member", "apps/api",
                "--context", "ci",
                "--check", "execution-context",
                "--coverage-dir", "target/coverage",
                "--cwd", workspaceDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok execution-context apps/api coverage-reports CI coverage preflight found Jacoco execution data, 1 XML report, and 1 HTML report."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiRejectsMissingWorkspaceCoverageReportsWhenConfigured() throws IOException {
        Path workspaceDir = tempDir.resolve("check-context-ci-workspace-coverage-missing");
        Path apiDir = workspaceDir.resolve("apps/api");
        Files.createDirectories(apiDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "check-context-ci-workspace-coverage-missing"
                members = ["apps/api"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api"));
        Files.writeString(workspaceDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "check",
                "--workspace",
                "--member", "apps/api",
                "--context", "ci",
                "--check", "execution-context",
                "--coverage-dir", "target/coverage",
                "--cwd", workspaceDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error execution-context apps/api target/coverage CI context expected coverage reports, but the coverage directory is missing."));
        assertTrue(result.stdout().contains("next: Run `zolt coverage` from each selected member so coverage evidence exists under target/coverage"));
        assertEquals("", result.stderr());
    }
}
