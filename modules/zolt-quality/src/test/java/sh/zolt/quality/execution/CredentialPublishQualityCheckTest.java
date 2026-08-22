package sh.zolt.quality.execution;

import sh.zolt.publish.ManifestPublishSettingsLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.ProjectConfig;
import sh.zolt.quality.QualityCheckContext;
import sh.zolt.quality.QualityCheckResult;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CredentialPublishQualityCheckTest {
    private final ManifestProjectConfigLoader manifestLoader = new ManifestProjectConfigLoader();

    @TempDir
    private Path tempDir;

    @Test
    void publishCredentialCheckSkipsWhenPublishIsNotConfigured() throws IOException {
        Path projectDir = tempDir.resolve("publish-not-configured");
        Files.createDirectories(projectDir);
        String toml = """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """;
        Files.writeString(projectDir.resolve("zolt.toml"), toml);
        ProjectConfig config = manifestLoader.load(toml);
        CredentialQualityCheck check = new CredentialQualityCheck(new ManifestPublishSettingsLoader(), Map.<String, String>of()::get);

        assertEquals(List.of(), check.checkPublishCredentials(
                Optional.empty(),
                projectDir,
                config,
                QualityCheckContext.CI));
    }

    @Test
    void publishCredentialCheckReportsMissingEnvironmentVariablesByNameOnly() throws IOException {
        Path projectDir = tempDir.resolve("publish-missing-env");
        String toml = publishCredentialToml("""
                credentials = "publish-creds"
                """, """

                [credentials.publish-creds]
                usernameEnv = "PUBLISH_USERNAME"
                passwordEnv = "PUBLISH_ACCESS_TOKEN"
                """);
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), toml);
        ProjectConfig config = manifestLoader.load(toml);
        CredentialQualityCheck check = new CredentialQualityCheck(new ManifestPublishSettingsLoader(), Map.<String, String>of()::get);

        QualityCheckResult result = check.checkPublishCredentials(
                Optional.empty(),
                projectDir,
                config,
                QualityCheckContext.CI).getFirst();

        assertEquals("[repositoryCredentials.publish-creds]", result.subject());
        assertEquals(
                "CI context requires environment variables PUBLISH_USERNAME, PUBLISH_ACCESS_TOKEN for publish repository `company-releases` credentials `publish-creds` before publish work starts.",
                result.message());
        assertEquals(
                "Set the named CI secrets and rerun `zolt check --context ci`. Secret values are never printed.",
                result.nextStep());
    }

    @Test
    void publishCredentialCheckReportsCredentialedPublishRepositorySummary() throws IOException {
        Path projectDir = tempDir.resolve("publish-credentials-ok");
        Files.createDirectories(projectDir);
        String toml = """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                credentials = "publish-creds"

                [credentials.publish-creds]
                usernameEnv = "PUBLISH_USERNAME"
                passwordEnv = "PUBLISH_ACCESS_TOKEN"
                """;
        Files.writeString(projectDir.resolve("zolt.toml"), toml);
        ProjectConfig config = manifestLoader.load(toml);
        CredentialQualityCheck check = new CredentialQualityCheck(new ManifestPublishSettingsLoader(), Map.of(
                        "PUBLISH_USERNAME", "publisher",
                        "PUBLISH_ACCESS_TOKEN", "token-123")
                ::get);

        QualityCheckResult result = check.checkPublishCredentials(
                Optional.empty(),
                projectDir,
                config,
                QualityCheckContext.CI).getFirst();

        assertEquals("publish-credentials", result.subject());
        assertEquals("CI publish credential preflight passed for 1 credentialed publish repository.", result.message());
        assertEquals("", result.nextStep());
    }

    private static void assertDoesNotLeakSecret(QualityCheckResult result) {
        String rendered = result.message() + "\n" + result.nextStep();
        assertFalse(rendered.contains("publish-user"));
        assertFalse(rendered.contains("super-secret"));
    }

    private static String publishCredentialToml(String repositoryBody, String credentialBody) {
        return """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                %s
                %s
                """.formatted(repositoryBody, credentialBody);
    }
}
