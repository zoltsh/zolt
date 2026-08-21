package sh.zolt.cli.build;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.generatedSourceConfig;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlanCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void planReportsTypedPipelineAndBlockersWithoutExecutingWork() throws IOException {
        Path projectDir = tempDir.resolve("plan-blocked");
        Files.createDirectories(projectDir.resolve("src/main/openapi"));
        Files.writeString(projectDir.resolve("src/main/openapi/api.yaml"), "openapi: 3.1.0\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("plan-blocked")
                + generatedSourceConfig("main", "openapi", "target/generated/sources/openapi", "src/main/openapi/api.yaml", true)
                + """

                [resources.filter]
                include = ["**/*.properties"]

                [resources.tokens]
                project-version = { project = "version" }

                [package]
                mode = "spring-boot-war"
                """);

        CommandResult result = execute("plan", "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("Zolt plan"));
        assertTrue(result.stdout().contains("Target: package"));
        assertTrue(result.stdout().contains("Status: blocked"));
        assertTrue(result.stdout().contains("- lockfile [resolve] blocked"));
        assertTrue(result.stdout().contains("blocker missing-lockfile: zolt.lock is missing"));
        assertTrue(result.stdout().contains("- generate-main-openapi [generated-source] blocked"));
        assertTrue(result.stdout().contains("blocker missing-generated-source-output"));
        assertTrue(result.stdout().contains("- process-main-resources [resources] ready"));
        assertTrue(result.stdout().contains("tokens: [project-version]"));
        assertTrue(result.stdout().contains("- assemble-package [package] blocked"));
        assertTrue(result.stdout().contains("blocker missing-main-class"));
        assertEquals("", result.stderr());
    }

    @Test
    void planAcceptsVisibleProjectDirectoryOption() throws IOException {
        Path projectDir = tempDir.resolve("plan-directory");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("plan-directory"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "plan",
                "--directory", projectDir.toString(),
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"project\": \"plan-directory\""));
        assertTrue(result.stdout().contains("\"target\": \"package\""));
        assertEquals("", result.stderr());
    }

    @Test
    void planJsonRedactsTestEnvironmentValues() throws IOException {
        Path projectDir = tempDir.resolve("plan-json");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("plan-json") + """

                [test.runtime]
                jvmArgs = ["--add-opens=java.base/java.lang=ALL-UNNAMED"]
                properties = { "logs.dir" = "${project.root}/test-logs" }
                env = { TZ = "America/Chicago" }
                events = ["failed", "skipped"]
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "plan",
                "--target", "test",
                "--reports-dir", "target/test-reports",
                "--format", "json",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"target\": \"test\""));
        assertTrue(result.stdout().contains("\"id\": \"run-tests\""));
        assertTrue(result.stdout().contains("\"target/test-reports\""));
        assertTrue(result.stdout().contains("\"environment: [TZ] (values redacted)\""));
        assertFalse(result.stdout().contains("America/Chicago"));
        assertEquals("", result.stderr());
    }

    @Test
    void planRejectsUnsafeReportsDirectory() throws IOException {
        Path projectDir = tempDir.resolve("plan-unsafe-reports");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("plan-unsafe-reports"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "plan",
                "--target", "test",
                "--reports-dir", "../reports",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: Invalid --reports-dir path `../reports` resolved to "));
        assertTrue(result.stderr().contains("Use a project-relative path under "
                + projectDir.toAbsolutePath().normalize()));
    }

    @Test
    void planCiIncludesExplicitCoverageAndPublishNodes() throws IOException {
        Path projectDir = tempDir.resolve("plan-ci");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("plan-ci"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute("plan", "--target", "ci", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("- coverage [coverage] planned"));
        assertTrue(result.stdout().contains("command: zolt coverage"));
        assertTrue(result.stdout().contains("- publish-dry-run [publish] planned"));
        assertTrue(result.stdout().contains("mode: dry-run"));
    }

}
