package sh.zolt.cli.publish;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.CliTestSupport.sha256;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestPackageEvidence;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublishCommandCredentialTest {
    @TempDir
    private Path tempDir;

    @Test
    void publishDryRunRoutesSnapshotAndReportsMissingCredentialsAndArtifact() throws IOException {
        Path projectDir = tempDir.resolve("publish-dry-run-snapshot-blocked");
        writeProjectConfig(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), Files.readString(projectDir.resolve("zolt.toml"))
                .replace("version = \"0.1.0\"", "version = \"0.1.0-SNAPSHOT\"") + """

                [publish]
                release = "company-releases"
                snapshot = "company-snapshots"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"

                [publish.repositories.company-snapshots]
                url = "https://repo.example.test/snapshots"
                credentials = "publish-creds"

                [credentials.publish-creds]
                usernameEnv = "ZOLT_TEST_MISSING_PUBLISH_USERNAME"
                passwordEnv = "ZOLT_TEST_MISSING_PUBLISH_PASSWORD"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "publish",
                "--dry-run",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("Version kind: snapshot"));
        assertTrue(result.stdout().contains("Target repository: company-snapshots"));
        assertTrue(result.stdout().contains("Status: blocked"));
        assertTrue(result.stdout().contains("missing credential environment variables ZOLT_TEST_MISSING_PUBLISH_USERNAME, ZOLT_TEST_MISSING_PUBLISH_PASSWORD"));
        assertTrue(result.stdout().contains("missing artifact: run `zolt package`"));
        assertFalse(result.stdout().contains("repo.example.test/snapshots@"));
        assertEquals("", result.stderr());
    }

    @Test
    void publishDryRunBlocksAndRedactsEmbeddedRepositoryCredentials() throws IOException {
        Path projectDir = tempDir.resolve("publish-dry-run-redacted-url");
        writeProjectConfig(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), Files.readString(projectDir.resolve("zolt.toml")) + """

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://publish-user:super-secret@repo.example.test/releases"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "publish",
                "--dry-run",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("Target URL: https://***@repo.example.test/releases"));
        assertTrue(result.stdout().contains("Status: blocked"));
        assertTrue(result.stdout().contains("publish repository `company-releases` URL contains embedded credentials"));
        assertTrue(result.stdout().contains("Move credentials to [repositoryCredentials] environment references."));
        assertFalse(result.stdout().contains("publish-user"));
        assertFalse(result.stdout().contains("super-secret"));
        assertFalse(result.stderr().contains("publish-user"));
        assertFalse(result.stderr().contains("super-secret"));
    }

    @Test
    void publishDryRunRejectsSnapshotVersionWithoutSnapshotTarget() throws IOException {
        Path projectDir = tempDir.resolve("publish-dry-run-missing-snapshot-target");
        writeProjectConfig(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), Files.readString(projectDir.resolve("zolt.toml"))
                .replace("version = \"0.1.0\"", "version = \"0.1.0-SNAPSHOT\"") + """

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """);

        CommandResult result = execute(
                "publish",
                "--dry-run",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Project version `0.1.0-SNAPSHOT` requires [publish].snapshotRepository"));
        assertEquals("", result.stdout());
    }

    @Test
    void centralPublishIgnoresUnrelatedInternalRepositoryCredentialsWhilePlainPublishStillBlocks()
            throws IOException {
        Path projectDir = tempDir.resolve("central-vs-internal-nexus");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/nexus-central-0.1.0.jar");
        Path sourcesArtifact = projectDir.resolve("target/nexus-central-0.1.0-sources.jar");
        Path javadocArtifact = projectDir.resolve("target/nexus-central-0.1.0-javadoc.jar");
        Files.writeString(artifact, "fake package\n");
        Files.writeString(sourcesArtifact, "fake sources\n");
        Files.writeString(javadocArtifact, "fake javadoc\n");
        Files.writeString(projectDir.resolve("target/nexus-central-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/nexus-central-0.1.0.jar",
                  "archiveSha256": "%s",
                  "artifacts": [
                    { "classifier": "main", "type": "thin", "path": "target/nexus-central-0.1.0.jar", "entries": 1, "sha256": "%s" },
                    { "classifier": "sources", "type": "jar", "path": "target/nexus-central-0.1.0-sources.jar", "entries": 1, "sha256": "%s" },
                    { "classifier": "javadoc", "type": "jar", "path": "target/nexus-central-0.1.0-javadoc.jar", "entries": 1, "sha256": "%s" }
                  ]
                }
                """.formatted(sha256(artifact), sha256(artifact), sha256(sourcesArtifact), sha256(javadocArtifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        // A configured internal Nexus whose credential env vars are UNSET, plus a Central deployment.
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("nexus-central") + """

                [package]
                sources = true
                javadoc = true

                [project.scm]
                url = "https://github.com/example/nexus-central"
                connection = "scm:git:https://github.com/example/nexus-central.git"

                [project.developers.ada]
                name = "Ada Lovelace"
                email = "ada@example.com"

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                credentials = "publish-creds"

                [credentials.publish-creds]
                usernameEnv = "ZOLT_TEST_UNSET_NEXUS_USERNAME"
                passwordEnv = "ZOLT_TEST_UNSET_NEXUS_PASSWORD"

                [publish.signing]
                method = "gpg"
                keyId = "ABCDEF0123456789"

                [publish.central]
                tokenEnv = "ZOLT_TEST_UNSET_CENTRAL_TOKEN"
                mode = "manual"
                """);
        CliTestPackageEvidence.write(projectDir);

        // --central routes to the Portal: the unrelated internal Nexus credentials never enter its
        // validation, so Central readiness alone governs and the deployment is ready.
        CommandResult central = execute("publish", "--dry-run", "--central", "--cwd", projectDir.toString());
        assertEquals(0, central.exitCode(), central.stdout() + central.stderr());
        assertTrue(central.stdout().contains("Central status: ready"), central.stdout());
        assertFalse(central.stdout().contains("ZOLT_TEST_UNSET_NEXUS_USERNAME"), central.stdout());
        assertFalse(central.stdout().contains("missing credential environment variables"), central.stdout());

        // The plain path targets the internal Nexus, so its missing credentials still block publishing.
        CommandResult plain = execute("publish", "--dry-run", "--cwd", projectDir.toString());
        assertEquals(1, plain.exitCode());
        assertTrue(plain.stdout().contains("Target repository: company-releases"), plain.stdout());
        assertTrue(plain.stdout().contains("Status: blocked"), plain.stdout());
        assertTrue(
                plain.stdout().contains(
                        "missing credential environment variables ZOLT_TEST_UNSET_NEXUS_USERNAME,"
                                + " ZOLT_TEST_UNSET_NEXUS_PASSWORD"),
                plain.stdout());
    }

    private static void writeProjectConfig(Path projectDir) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo") + """
                main = "com.example.Main"

                [repositories.test]
                url = "https://repo.maven.apache.org/maven2"

                [dependencies]

                [dependencies.test]
                """);
    }
}
