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

final class PublishCommandReleaseContextTest {
    @TempDir
    private Path tempDir;

    @Test
    void publishDryRunReleaseContextRequiresReleaseMetadataAndSupplementalArtifacts() throws IOException {
        Path projectDir = tempDir.resolve("publish-dry-run-release-context-blocked");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/publish-dry-run-release-context-blocked-0.1.0.jar");
        Files.writeString(artifact, "fake package\n");
        Files.writeString(projectDir.resolve("target/publish-dry-run-release-context-blocked-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/publish-dry-run-release-context-blocked-0.1.0.jar",
                  "archiveSha256": "%s",
                  "artifacts": [
                    {
                      "classifier": "main",
                      "type": "thin",
                      "path": "target/publish-dry-run-release-context-blocked-0.1.0.jar",
                      "entries": 1,
                      "sha256": "%s"
                    }
                  ]
                }
                """.formatted(sha256(artifact), sha256(artifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("publish-dry-run-release-context-blocked") + """

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """);
        CliTestPackageEvidence.write(projectDir);

        CommandResult result = execute(
                "publish",
                "--dry-run",
                "--context", "release",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("Context: release"));
        assertTrue(result.stdout().contains("Policy source: built-in release context"));
        assertTrue(result.stdout().contains("release context requires [package.metadata].name."));
        assertTrue(result.stdout().contains("release context requires [package.metadata].license."));
        assertTrue(result.stdout().contains("release context requires a sources jar"));
        assertTrue(result.stdout().contains("release context requires a javadoc jar"));
        assertTrue(result.stdout().contains("Status: blocked"));
        assertEquals("", result.stderr());
    }

    @Test
    void publishDryRunReleaseContextAcceptsCompleteReleaseMetadata() throws IOException {
        Path projectDir = tempDir.resolve("publish-dry-run-release-context-ok");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/publish-dry-run-release-context-ok-0.1.0.jar");
        Path sourcesArtifact = projectDir.resolve("target/publish-dry-run-release-context-ok-0.1.0-sources.jar");
        Path javadocArtifact = projectDir.resolve("target/publish-dry-run-release-context-ok-0.1.0-javadoc.jar");
        Files.writeString(artifact, "fake package\n");
        Files.writeString(sourcesArtifact, "fake sources\n");
        Files.writeString(javadocArtifact, "fake javadoc\n");
        Files.writeString(projectDir.resolve("target/publish-dry-run-release-context-ok-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/publish-dry-run-release-context-ok-0.1.0.jar",
                  "archiveSha256": "%s",
                  "artifacts": [
                    {
                      "classifier": "main",
                      "type": "thin",
                      "path": "target/publish-dry-run-release-context-ok-0.1.0.jar",
                      "entries": 1,
                      "sha256": "%s"
                    },
                    {
                      "classifier": "sources",
                      "type": "jar",
                      "path": "target/publish-dry-run-release-context-ok-0.1.0-sources.jar",
                      "entries": 1,
                      "sha256": "%s"
                    },
                    {
                      "classifier": "javadoc",
                      "type": "jar",
                      "path": "target/publish-dry-run-release-context-ok-0.1.0-javadoc.jar",
                      "entries": 1,
                      "sha256": "%s"
                    }
                  ]
                }
                """.formatted(sha256(artifact), sha256(artifact), sha256(sourcesArtifact), sha256(javadocArtifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("publish-dry-run-release-context-ok") + """

                [package]
                sources = true
                javadoc = true

                [project.scm]
                url = "https://example.com/release-context.git"

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """);
        CliTestPackageEvidence.write(projectDir);

        CommandResult result = execute(
                "publish",
                "--dry-run",
                "--context", "release",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Context: release"));
        assertTrue(result.stdout().contains("Policy source: built-in release context"));
        assertTrue(result.stdout().contains("Status: ready"));
        assertTrue(result.stdout().contains("- sources: target/publish-dry-run-release-context-ok-0.1.0-sources.jar"));
        assertTrue(result.stdout().contains("- javadoc: target/publish-dry-run-release-context-ok-0.1.0-javadoc.jar"));
        Path pom = projectDir.resolve("target/publish/publish-dry-run-release-context-ok-0.1.0.pom");
        String pomXml = Files.readString(pom);
        assertTrue(pomXml.contains("<licenses>"));
        assertTrue(pomXml.contains("<name>Apache-2.0</name>"));
        assertTrue(pomXml.contains("<developers>"));
        assertTrue(pomXml.contains("<name>Example Team</name>"));
        assertTrue(pomXml.contains("<scm>"));
        assertTrue(pomXml.contains("<issueManagement>"));
        assertEquals("", result.stderr());
    }

}
