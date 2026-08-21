package sh.zolt.cli.publish;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.CliTestSupport.sha256;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublishCommandReleaseContextSnapshotTest {
    @TempDir
    private Path tempDir;

    @Test
    void publishDryRunReleaseContextRejectsSnapshotVersions() throws IOException {
        Path projectDir = tempDir.resolve("publish-dry-run-release-context-snapshot");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/publish-dry-run-release-context-snapshot-0.1.0-SNAPSHOT.jar");
        Path sourcesArtifact = projectDir.resolve("target/publish-dry-run-release-context-snapshot-0.1.0-SNAPSHOT-sources.jar");
        Path javadocArtifact = projectDir.resolve("target/publish-dry-run-release-context-snapshot-0.1.0-SNAPSHOT-javadoc.jar");
        Files.writeString(artifact, "fake package\n");
        Files.writeString(sourcesArtifact, "fake sources\n");
        Files.writeString(javadocArtifact, "fake javadoc\n");
        Files.writeString(projectDir.resolve("target/publish-dry-run-release-context-snapshot-0.1.0-SNAPSHOT.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/publish-dry-run-release-context-snapshot-0.1.0-SNAPSHOT.jar",
                  "archiveSha256": "%s",
                  "artifacts": [
                    {
                      "classifier": "main",
                      "type": "thin",
                      "path": "target/publish-dry-run-release-context-snapshot-0.1.0-SNAPSHOT.jar",
                      "entries": 1,
                      "sha256": "%s"
                    },
                    {
                      "classifier": "sources",
                      "type": "jar",
                      "path": "target/publish-dry-run-release-context-snapshot-0.1.0-SNAPSHOT-sources.jar",
                      "entries": 1,
                      "sha256": "%s"
                    },
                    {
                      "classifier": "javadoc",
                      "type": "jar",
                      "path": "target/publish-dry-run-release-context-snapshot-0.1.0-SNAPSHOT-javadoc.jar",
                      "entries": 1,
                      "sha256": "%s"
                    }
                  ]
                }
                """.formatted(sha256(artifact), sha256(artifact), sha256(sourcesArtifact), sha256(javadocArtifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("publish-dry-run-release-context-snapshot")
                .replace("version = \"0.1.0\"", "version = \"0.1.0-SNAPSHOT\"") + """
                description = "Release context snapshot fixture."
                url = "https://example.com/release-context-snapshot"
                issues = "https://example.com/release-context-snapshot/issues"
                license = "Apache-2.0"

                [project.developers.team]
                name = "Example Team"

                [package]
                sources = true
                javadoc = true

                [project.scm]
                url = "https://example.com/release-context-snapshot.git"

                [publish]
                snapshot = "company-snapshots"

                [publish.repositories.company-snapshots]
                url = "https://repo.example.test/snapshots"
                """);

        CommandResult result = execute(
                "publish",
                "--dry-run",
                "--context", "release",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("Version kind: snapshot"));
        assertTrue(result.stdout().contains("Context: release"));
        assertTrue(result.stdout().contains("Policy source: built-in release context"));
        assertTrue(result.stdout().contains("release context rejects SNAPSHOT version `0.1.0-SNAPSHOT`"));
        assertTrue(result.stdout().contains("Status: blocked"));
        assertEquals("", result.stderr());
    }
}
