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

final class CheckExecutionContextEvidenceCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkContextCiRejectsMissingJUnitReportsWhenConfigured() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-missing-reports");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-missing-reports"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--reports-dir", "target/test-reports",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error execution-context target/test-reports CI context expected JUnit XML reports, but the report directory is missing."));
        assertTrue(result.stdout().contains("next: Run `zolt test --reports-dir target/test-reports`"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiRejectsUnsafeReportsDirectory() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-unsafe-reports");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-unsafe-reports"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--reports-dir", "../reports",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error execution-context --reports-dir Invalid --reports-dir path `../reports` resolved to "));
        assertTrue(result.stdout().contains("Use a project-relative path under "
                + projectDir.toAbsolutePath().normalize()));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiAcceptsJUnitReportsWhenConfigured() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-reports-ok");
        Path reportsDir = projectDir.resolve("target/test-reports");
        Files.createDirectories(reportsDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-reports-ok"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(reportsDir.resolve("TEST-demo.xml"), "<testsuite tests=\"1\" failures=\"0\"/>\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--reports-dir", "target/test-reports",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok execution-context test-reports CI test report preflight found 1 JUnit XML report."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiRejectsMissingShardJUnitReportsWhenManifestExists() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-missing-shard-reports");
        Path reportsDir = projectDir.resolve("target/test-reports");
        Files.createDirectories(reportsDir);
        Files.createDirectories(projectDir.resolve("target/test-shards/fast_suite_"));
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-missing-shard-reports"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(reportsDir.resolve("TEST-other.xml"), "<testsuite tests=\"1\" failures=\"0\"/>\n");
        Files.writeString(projectDir.resolve("target/test-shards/fast_suite_/shard-1-of-2.json"), shardManifest("fast suite!", 1, 2, false));

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--reports-dir", "target/test-reports",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error execution-context target/test-reports/shards/fast_suite_/shard-1-of-2 CI context expected JUnit XML reports for shard `fast suite!/shard-1-of-2`, but none were found."));
        assertTrue(result.stdout().contains("next: Run `zolt test --suite \"fast suite!\" --shard 1/2 --reports-dir target/test-reports`"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiRejectsMissingCoverageReportsWhenConfigured() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-missing-coverage");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-missing-coverage"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--coverage-dir", "target/coverage",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error execution-context target/coverage CI context expected coverage reports, but the coverage directory is missing."));
        assertTrue(result.stdout().contains("next: Run `zolt coverage` so coverage evidence exists under target/coverage"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiRejectsUnsafeCoverageDirectory() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-unsafe-coverage");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-unsafe-coverage"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--coverage-dir", "../coverage",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error execution-context --coverage-dir Invalid --coverage-dir path `../coverage` resolved to "));
        assertTrue(result.stdout().contains("Use a project-relative path under "
                + projectDir.toAbsolutePath().normalize()));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiAcceptsCoverageReportsWhenConfigured() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-coverage-ok");
        Path coverageDir = projectDir.resolve("target/coverage");
        Files.createDirectories(coverageDir.resolve("html"));
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-coverage-ok"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(coverageDir.resolve("jacoco.exec"), "exec\n");
        Files.writeString(coverageDir.resolve("jacoco.xml"), "<report name=\"demo\"/>\n");
        Files.writeString(coverageDir.resolve("html/index.html"), "<!doctype html>\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--coverage-dir", "target/coverage",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok execution-context coverage-reports CI coverage preflight found Jacoco execution data, 1 XML report, and 1 HTML report."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiAcceptsShardCoverageReportsWhenManifestExists() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-shard-coverage-ok");
        Path coverageDir = projectDir.resolve("target/coverage/shards/fast/shard-1-of-2");
        Files.createDirectories(coverageDir.resolve("html"));
        Files.createDirectories(projectDir.resolve("target/test-shards/fast"));
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-shard-coverage-ok"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(projectDir.resolve("target/test-shards/fast/shard-1-of-2.json"), shardManifest("fast", 1, 2, false));
        Files.writeString(coverageDir.resolve("jacoco.exec"), "exec\n");
        Files.writeString(coverageDir.resolve("jacoco.xml"), "<report name=\"demo\"/>\n");
        Files.writeString(coverageDir.resolve("html/index.html"), "<!doctype html>\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--coverage-dir", "target/coverage",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok execution-context coverage-reports CI coverage preflight found split Jacoco evidence."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiRejectsMissingWorkerCoverageWhenManifestExists() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-missing-worker-coverage");
        Path coverageDir = projectDir.resolve("target/coverage");
        Files.createDirectories(coverageDir.resolve("html"));
        Files.createDirectories(coverageDir.resolve("workers"));
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-missing-worker-coverage"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(coverageDir.resolve("jacoco.exec"), "exec\n");
        Files.writeString(coverageDir.resolve("jacoco.xml"), "<report name=\"demo\"/>\n");
        Files.writeString(coverageDir.resolve("html/index.html"), "<!doctype html>\n");
        Files.writeString(coverageDir.resolve("workers/zolt-workers.json"), """
                {
                  "version": 1,
                  "workers": [
                    "wave-1-worker-1"
                  ]
                }
                """);

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--coverage-dir", "target/coverage",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error execution-context target/coverage/workers/wave-1-worker-1/jacoco.exec CI context expected Jacoco execution data for worker `wave-1-worker-1`, but jacoco.exec is missing."));
        assertTrue(result.stdout().contains("next: Rerun `zolt coverage` so worker coverage evidence is regenerated under target/coverage."));
        assertEquals("", result.stderr());
    }

    private static String shardManifest(String suite, int index, int total, boolean empty) {
        return """
                {
                  "version": 1,
                  "suite": "%s",
                  "shard": {
                    "index": %d,
                    "total": %d
                  },
                  "inventoryFingerprint": "sha256:demo",
                  "inventoryEntries": 2,
                  "selectedEntries": 1,
                  "empty": %s,
                  "entries": [
                    "com.example.DemoTest"
                  ]
                }
                """.formatted(suite, index, total, empty);
    }
}
