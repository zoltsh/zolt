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
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublishCommandEvidenceTest {
    @TempDir
    private Path tempDir;

    @Test
    void publishDryRunListsSupplementalArtifactsFromPackageEvidence() throws IOException {
        Path projectDir = tempDir.resolve("publish-dry-run-supplemental-artifacts");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/publish-dry-run-supplemental-artifacts-0.1.0.jar");
        Path sourcesArtifact = projectDir.resolve("target/publish-dry-run-supplemental-artifacts-0.1.0-sources.jar");
        Files.writeString(artifact, "fake package\n");
        Files.writeString(sourcesArtifact, "fake sources\n");
        Files.writeString(projectDir.resolve("target/publish-dry-run-supplemental-artifacts-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/publish-dry-run-supplemental-artifacts-0.1.0.jar",
                  "archiveSha256": "%s",
                  "artifacts": [
                    {
                      "classifier": "main",
                      "type": "thin",
                      "path": "target/publish-dry-run-supplemental-artifacts-0.1.0.jar",
                      "entries": 1,
                      "sha256": "%s"
                    },
                    {
                      "classifier": "sources",
                      "type": "jar",
                      "path": "target/publish-dry-run-supplemental-artifacts-0.1.0-sources.jar",
                      "entries": 1,
                      "sha256": "%s"
                    }
                  ]
                }
                """.formatted(sha256(artifact), sha256(artifact), sha256(sourcesArtifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("publish-dry-run-supplemental-artifacts") + """

                [package]
                sources = true

                [publish]
                releaseRepository = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """);
        CliTestPackageEvidence.write(projectDir);

        CommandResult result = execute(
                "publish",
                "--dry-run",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Supplemental artifacts:"));
        assertTrue(result.stdout().contains("- sources: target/publish-dry-run-supplemental-artifacts-0.1.0-sources.jar"));
        assertTrue(result.stdout().contains("upload path: com/example/publish-dry-run-supplemental-artifacts/0.1.0/publish-dry-run-supplemental-artifacts-0.1.0-sources.jar"));
        assertTrue(result.stdout().contains("Status: ready"));
        assertEquals("", result.stderr());
    }

    @Test
    void publishDryRunBlocksStalePackageEvidence() throws IOException {
        Path projectDir = tempDir.resolve("publish-dry-run-stale-package-evidence");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/publish-dry-run-stale-package-evidence-0.1.0.jar");
        Files.writeString(artifact, "fake package\n");
        Files.writeString(projectDir.resolve("target/publish-dry-run-stale-package-evidence-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/publish-dry-run-stale-package-evidence-0.1.0.jar",
                  "archiveSha256": "%s"
                }
                """.formatted(sha256(artifact)));
        Files.writeString(artifact, "tampered\n", StandardOpenOption.APPEND);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("publish-dry-run-stale-package-evidence") + """

                [publish]
                releaseRepository = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """);

        CommandResult result = execute(
                "publish",
                "--dry-run",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("Evidence: target/publish-dry-run-stale-package-evidence-0.1.0.jar.zolt-package.json"));
        assertTrue(result.stdout().contains("Status: blocked"));
        assertTrue(result.stdout().contains(
                "stale package evidence: package evidence schema `zolt.package-evidence.v1` is stale;"
                        + " expected `zolt.package-evidence.v3`"));
        assertTrue(result.stdout().contains(
                "Run `zolt package` to refresh"
                        + " target/publish-dry-run-stale-package-evidence-0.1.0.jar.zolt-package.json"));
        assertEquals("", result.stderr());
    }

    @Test
    void publishDryRunBlocksPackageEvidenceForDifferentArchive() throws IOException {
        Path projectDir = tempDir.resolve("publish-dry-run-evidence-archive-mismatch");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/publish-dry-run-evidence-archive-mismatch-0.1.0.jar");
        Files.writeString(artifact, "fake package\n");
        Files.writeString(projectDir.resolve("target/publish-dry-run-evidence-archive-mismatch-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/other-0.1.0.jar",
                  "archiveSha256": "%s"
                }
                """.formatted(sha256(artifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("publish-dry-run-evidence-archive-mismatch") + """

                [publish]
                releaseRepository = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """);

        CommandResult result = execute(
                "publish",
                "--dry-run",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("Status: blocked"));
        assertTrue(result.stdout().contains(
                "stale package evidence: package evidence describes target/other-0.1.0.jar"
                        + " but the current package plan selects"
                        + " target/publish-dry-run-evidence-archive-mismatch-0.1.0.jar"));
        assertTrue(result.stdout().contains(
                "Run `zolt package` to refresh"
                        + " target/publish-dry-run-evidence-archive-mismatch-0.1.0.jar.zolt-package.json"));
        assertEquals("", result.stderr());
    }
}
