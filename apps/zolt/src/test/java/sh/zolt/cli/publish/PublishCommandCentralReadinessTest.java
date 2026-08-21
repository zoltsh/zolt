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

final class PublishCommandCentralReadinessTest {
    @TempDir
    private Path tempDir;

    @Test
    void centralReadinessPassesEveryRequirementExceptSigningWhenMetadataIsComplete() throws IOException {
        Path projectDir = tempDir.resolve("central-ready");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/central-ready-0.1.0.jar");
        Path sourcesArtifact = projectDir.resolve("target/central-ready-0.1.0-sources.jar");
        Path javadocArtifact = projectDir.resolve("target/central-ready-0.1.0-javadoc.jar");
        Files.writeString(artifact, "fake package\n");
        Files.writeString(sourcesArtifact, "fake sources\n");
        Files.writeString(javadocArtifact, "fake javadoc\n");
        Files.writeString(projectDir.resolve("target/central-ready-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/central-ready-0.1.0.jar",
                  "archiveSha256": "%s",
                  "artifacts": [
                    { "classifier": "main", "type": "thin", "path": "target/central-ready-0.1.0.jar", "entries": 1, "sha256": "%s" },
                    { "classifier": "sources", "type": "jar", "path": "target/central-ready-0.1.0-sources.jar", "entries": 1, "sha256": "%s" },
                    { "classifier": "javadoc", "type": "jar", "path": "target/central-ready-0.1.0-javadoc.jar", "entries": 1, "sha256": "%s" }
                  ]
                }
                """.formatted(sha256(artifact), sha256(artifact), sha256(sourcesArtifact), sha256(javadocArtifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("central-ready") + """

                [package]
                sources = true
                javadoc = true

                [project.scm]
                url = "https://github.com/example/central-ready"
                connection = "scm:git:https://github.com/example/central-ready.git"

                [project.developers.ada]
                name = "Ada Lovelace"
                email = "ada@example.com"

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """);
        CliTestPackageEvidence.write(projectDir);

        CommandResult result = execute("publish", "--dry-run", "--central", "--cwd", projectDir.toString());

        // Only GPG signing is missing, so the deployment is not yet Central-ready.
        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("Maven Central readiness:"), result.stdout());
        assertTrue(result.stdout().contains("- [x] release version"), result.stdout());
        assertTrue(result.stdout().contains("- [x] project name"), result.stdout());
        assertTrue(result.stdout().contains("- [x] license name and url"), result.stdout());
        assertTrue(result.stdout().contains("- [x] developer information"), result.stdout());
        assertTrue(result.stdout().contains("- [x] scm url and connection"), result.stdout());
        assertTrue(result.stdout().contains("- [x] sources jar"), result.stdout());
        assertTrue(result.stdout().contains("- [x] javadoc jar"), result.stdout());
        assertTrue(result.stdout().contains("- [x] checksums"), result.stdout());
        assertTrue(result.stdout().contains("- [ ] gpg signatures"), result.stdout());
        assertTrue(result.stdout().contains("Next: Enable [publish.signing]"), result.stdout());
        assertTrue(result.stdout().contains("Central status: not ready"), result.stdout());
    }

    @Test
    void centralReadinessIsSatisfiedWhenSigningIsEnabledAndMetadataIsComplete() throws IOException {
        Path projectDir = tempDir.resolve("central-signed");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/central-signed-0.1.0.jar");
        Path sourcesArtifact = projectDir.resolve("target/central-signed-0.1.0-sources.jar");
        Path javadocArtifact = projectDir.resolve("target/central-signed-0.1.0-javadoc.jar");
        Files.writeString(artifact, "fake package\n");
        Files.writeString(sourcesArtifact, "fake sources\n");
        Files.writeString(javadocArtifact, "fake javadoc\n");
        Files.writeString(projectDir.resolve("target/central-signed-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/central-signed-0.1.0.jar",
                  "archiveSha256": "%s",
                  "artifacts": [
                    { "classifier": "main", "type": "thin", "path": "target/central-signed-0.1.0.jar", "entries": 1, "sha256": "%s" },
                    { "classifier": "sources", "type": "jar", "path": "target/central-signed-0.1.0-sources.jar", "entries": 1, "sha256": "%s" },
                    { "classifier": "javadoc", "type": "jar", "path": "target/central-signed-0.1.0-javadoc.jar", "entries": 1, "sha256": "%s" }
                  ]
                }
                """.formatted(sha256(artifact), sha256(artifact), sha256(sourcesArtifact), sha256(javadocArtifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("central-signed") + """

                [package]
                sources = true
                javadoc = true

                [project.scm]
                url = "https://github.com/example/central-signed"
                connection = "scm:git:https://github.com/example/central-signed.git"

                [project.developers.ada]
                name = "Ada Lovelace"
                email = "ada@example.com"

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"

                [publish.signing]
                method = "gpg"
                keyId = "ABCDEF0123456789"
                """);
        CliTestPackageEvidence.write(projectDir);

        CommandResult result = execute("publish", "--dry-run", "--central", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode(), result.stdout());
        assertTrue(result.stdout().contains("- [x] gpg signatures"), result.stdout());
        assertTrue(result.stdout().contains("Central status: ready"), result.stdout());
    }

    @Test
    void centralReadinessListsMissingMetadataWithActionableRemediation() throws IOException {
        Path projectDir = tempDir.resolve("central-bare");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/central-bare-0.1.0.jar");
        Files.writeString(artifact, "fake package\n");
        Files.writeString(projectDir.resolve("target/central-bare-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/central-bare-0.1.0.jar",
                  "archiveSha256": "%s"
                }
                """.formatted(sha256(artifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("central-bare") + """

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """);

        CommandResult result = execute("publish", "--dry-run", "--central", "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("- [x] release version"), result.stdout());
        assertTrue(result.stdout().contains("- [ ] project name"), result.stdout());
        assertTrue(result.stdout().contains("Next: Add [package.metadata].name."), result.stdout());
        assertTrue(result.stdout().contains("- [ ] license name and url"), result.stdout());
        assertTrue(result.stdout().contains("- [ ] developer information"), result.stdout());
        assertTrue(result.stdout().contains("- [ ] sources jar"), result.stdout());
        assertTrue(result.stdout().contains("- [ ] javadoc jar"), result.stdout());
        assertTrue(result.stdout().contains("Central status: not ready"), result.stdout());
    }

    @Test
    void centralUploadWithoutPublishConfigurationReportsActionableError() throws IOException {
        Path projectDir = tempDir.resolve("central-guard");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("central-guard"));

        CommandResult result = execute("publish", "--central", "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: No [publish] configuration found."), result.stderr());
    }
}
