package sh.zolt.cli.publish;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.CliTestSupport.sha256;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestPackageEvidence;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckExecutionContextPublishDryRunCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkContextCiAcceptsPublishDryRunPreflightWhenRequired() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-publish-dry-run-ok");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/check-context-ci-publish-dry-run-ok-0.1.0.jar");
        Path sourcesArtifact = projectDir.resolve("target/check-context-ci-publish-dry-run-ok-0.1.0-sources.jar");
        Files.writeString(artifact, "fake package\n");
        Files.writeString(sourcesArtifact, "fake sources\n");
        Files.writeString(projectDir.resolve("target/check-context-ci-publish-dry-run-ok-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/check-context-ci-publish-dry-run-ok-0.1.0.jar",
                  "archiveSha256": "%s",
                  "artifacts": [
                    {
                      "classifier": "main",
                      "type": "thin",
                      "path": "target/check-context-ci-publish-dry-run-ok-0.1.0.jar",
                      "entries": 1,
                      "sha256": "%s"
                    },
                    {
                      "classifier": "sources",
                      "type": "jar",
                      "path": "target/check-context-ci-publish-dry-run-ok-0.1.0-sources.jar",
                      "entries": 1,
                      "sha256": "%s"
                    }
                  ]
                }
                """.formatted(sha256(artifact), sha256(artifact), sha256(sourcesArtifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-publish-dry-run-ok") + """

                [package]
                sources = true

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """);
        CliTestPackageEvidence.write(projectDir);

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--require-publish-dry-run",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok execution-context publish-dry-run CI publish dry-run preflight is ready for com.example:check-context-ci-publish-dry-run-ok:0.1.0"));
        assertTrue(result.stdout().contains("with 2 artifacts and generated POM metadata."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiReportsPublishDryRunBlockersWhenRequired() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-publish-dry-run-blocked");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-publish-dry-run-blocked") + """

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--require-publish-dry-run",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error execution-context publish-dry-run CI publish dry-run blocker: missing artifact: run `zolt package`"));
        assertTrue(result.stdout().contains("next: Run `zolt publish --dry-run` and resolve the reported blocker before release CI."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiPublishDryRunJsonOutputIsStable() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-publish-dry-run-json");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-publish-dry-run-json"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--require-publish-dry-run",
                "--format", "json",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("\"id\":\"execution-context\""));
        assertTrue(result.stdout().contains("\"subject\":\"publish-dry-run\""));
        assertTrue(result.stdout().contains("\"status\":\"failed\""));
        assertTrue(result.stdout().contains("CI publish dry-run preflight failed"));
        assertTrue(result.stdout().contains("No [publish] configuration found"));
        assertEquals("", result.stderr());
    }
}
