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

final class PublishCommandCentralUploadTest {
    @TempDir
    private Path tempDir;

    @Test
    void dryRunCentralAssemblesBundleLocallyWithoutNetwork() throws IOException {
        Path projectDir = writeCentralProject("central-dry");

        CommandResult result = execute("publish", "--dry-run", "--central", "--cwd", projectDir.toString());

        // Signing is not enabled, so the deployment is not yet ready, but the bundle is still assembled locally.
        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("Central bundle: target/publish/central-bundle.zip"), result.stdout());
        assertTrue(result.stdout().contains("com/example/central-dry/0.1.0/central-dry-0.1.0.jar.sha1"), result.stdout());
        assertTrue(result.stdout().contains("com/example/central-dry/0.1.0/central-dry-0.1.0.pom.md5"), result.stdout());
        assertTrue(Files.isRegularFile(projectDir.resolve("target/publish/central-bundle.zip")));
    }

    @Test
    void centralUploadIsBlockedUntilReadinessIsSatisfied() throws IOException {
        Path projectDir = writeCentralProject("central-block");

        CommandResult result = execute("publish", "--central", "--cwd", projectDir.toString());

        // The readiness gate stops the upload; no deployment is attempted.
        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("- [ ] gpg signatures"), result.stdout());
        assertTrue(result.stdout().contains("Central status: not ready"), result.stdout());
        assertTrue(result.stdout().contains("Next: Enable [publish.signing]"), result.stdout());
    }

    private Path writeCentralProject(String name) throws IOException {
        Path projectDir = tempDir.resolve(name);
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/" + name + "-0.1.0.jar");
        Path sourcesArtifact = projectDir.resolve("target/" + name + "-0.1.0-sources.jar");
        Path javadocArtifact = projectDir.resolve("target/" + name + "-0.1.0-javadoc.jar");
        Files.writeString(artifact, "fake package\n");
        Files.writeString(sourcesArtifact, "fake sources\n");
        Files.writeString(javadocArtifact, "fake javadoc\n");
        Files.writeString(projectDir.resolve("target/" + name + "-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/%1$s-0.1.0.jar",
                  "archiveSha256": "%2$s",
                  "artifacts": [
                    { "classifier": "main", "type": "thin", "path": "target/%1$s-0.1.0.jar", "entries": 1, "sha256": "%2$s" },
                    { "classifier": "sources", "type": "jar", "path": "target/%1$s-0.1.0-sources.jar", "entries": 1, "sha256": "%3$s" },
                    { "classifier": "javadoc", "type": "jar", "path": "target/%1$s-0.1.0-javadoc.jar", "entries": 1, "sha256": "%4$s" }
                  ]
                }
                """.formatted(name, sha256(artifact), sha256(sourcesArtifact), sha256(javadocArtifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig(name) + """

                [package]
                sources = true
                javadoc = true

                [package.metadata]
                name = "Central Library"
                description = "A Central-bound library."
                url = "https://example.com/central"
                license = "Apache-2.0"
                licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                scm = "https://github.com/example/central"
                scmConnection = "scm:git:https://github.com/example/central.git"

                [package.metadata.developer.ada]
                name = "Ada Lovelace"
                email = "ada@example.com"

                [publish.central]
                tokenEnv = "ZOLT_CENTRAL_TOKEN"
                publishingType = "automatic"
                """);
        CliTestPackageEvidence.write(projectDir);
        return projectDir;
    }
}
