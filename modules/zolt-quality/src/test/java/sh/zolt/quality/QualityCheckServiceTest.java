package sh.zolt.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QualityCheckServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void workspaceCheckWithoutWorkspaceConfigReturnsUnavailableResults() throws IOException {
        Path projectDir = tempDir.resolve("not-a-workspace");
        Files.createDirectories(projectDir);
        QualityCheckService service = new QualityCheckService(Map.<String, String>of()::get);

        QualityCheckReport report = service.check(new QualityCheckRequest(
                projectDir,
                tempDir.resolve("cache"),
                false,
                true,
                List.of(QualityCheckService.COMMAND_SURFACE, "mvn verify"),
                null,
                null,
                null,
                false,
                false,
                false,
                WorkspaceSelectionRequest.defaults()));

        assertEquals("error", report.status());
        assertTrue(report.workspace());
        QualityCheckResult workspaceFailure = report.checks().getFirst();
        assertEquals(QualityCheckService.COMMAND_SURFACE, workspaceFailure.id());
        assertEquals("workspace config", workspaceFailure.subject());
        assertEquals("No Zolt workspace was found for `zolt check --workspace`.", workspaceFailure.message());
        assertEquals(
                "Run from a workspace root or remove --workspace for a single-project check.",
                workspaceFailure.nextStep());
        QualityCheckResult unsupported = report.checks().get(1);
        assertEquals("unsupported-check", unsupported.id());
        assertEquals("mvn verify", unsupported.subject());
        assertEquals("Unsupported quality check `mvn verify`.", unsupported.message());
        assertTrue(unsupported.nextStep().contains("Use one of:"));
        assertTrue(unsupported.nextStep().contains(
                "Zolt does not run Maven goals, Gradle tasks, shell commands, or arbitrary hooks."));
    }

    @Test
    void malformedProjectConfigReturnsUnavailableResultsForRequestedChecks() throws IOException {
        Path projectDir = tempDir.resolve("malformed-project");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = 42
                """);
        QualityCheckService service = new QualityCheckService(Map.<String, String>of()::get);

        QualityCheckReport report = service.check(new QualityCheckRequest(
                projectDir,
                tempDir.resolve("cache"),
                false,
                false,
                List.of(QualityCheckService.LOCKFILE, "mvn verify"),
                null,
                null,
                null,
                false,
                false,
                false,
                WorkspaceSelectionRequest.defaults()));

        assertEquals("error", report.status());
        assertFalse(report.workspace());
        assertEquals(QualityCheckService.LOCKFILE, report.checks().getFirst().id());
        assertEquals("zolt.toml", report.checks().getFirst().subject());
        assertFalse(report.checks().getFirst().message().isBlank());
        assertEquals("Fix zolt.toml, then run `zolt check` again.", report.checks().getFirst().nextStep());
        assertEquals("unsupported-check", report.checks().get(1).id());
    }

    @Test
    void ciContextRejectsPlaceholderCredentialValuesWithoutPrintingThem() throws IOException {
        Path projectDir = tempDir.resolve("placeholder-credentials");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "placeholder-credentials"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.company]
                url = "https://repo.example.test/maven"
                credentials = "company-artifactory"

                [credentials.company-artifactory]
                usernameEnv = "ARTIFACTORY_USERNAME"
                passwordEnv = "ARTIFACTORY_ACCESS_TOKEN"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Map<String, String> environment = Map.of(
                "ARTIFACTORY_USERNAME", "read.only",
                "ARTIFACTORY_ACCESS_TOKEN", "ReadOnly");
        QualityCheckService service = new QualityCheckService(environment::get);

        QualityCheckReport report = service.check(new QualityCheckRequest(
                projectDir,
                tempDir.resolve("cache"),
                false,
                false,
                List.of(QualityCheckService.EXECUTION_CONTEXT),
                QualityCheckContext.CI,
                null,
                null,
                false,
                false,
                false,
                WorkspaceSelectionRequest.defaults()));

        assertEquals("error", report.status());
        String output = QualityCheckFormatter.text(report);
        assertTrue(output.contains("error execution-context [credentials.company-artifactory] CI context rejects placeholder credential values"));
        assertTrue(output.contains("ARTIFACTORY_USERNAME, ARTIFACTORY_ACCESS_TOKEN"));
        assertFalse(output.contains("read.only"));
        assertFalse(output.contains("ReadOnly"));
    }

    @Test
    void ciContextRejectsPlaceholderPublishCredentialValuesWithoutPrintingThem() throws IOException {
        Path projectDir = tempDir.resolve("placeholder-publish-credentials");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "placeholder-publish-credentials"
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
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Map<String, String> environment = Map.of(
                "PUBLISH_USERNAME", "dummy",
                "PUBLISH_ACCESS_TOKEN", "read.only");
        QualityCheckService service = new QualityCheckService(environment::get);

        QualityCheckReport report = service.check(new QualityCheckRequest(
                projectDir,
                tempDir.resolve("cache"),
                false,
                false,
                List.of(QualityCheckService.EXECUTION_CONTEXT),
                QualityCheckContext.CI,
                null,
                null,
                false,
                false,
                false,
                WorkspaceSelectionRequest.defaults()));

        assertEquals("error", report.status());
        String output = QualityCheckFormatter.text(report);
        assertTrue(output.contains("error execution-context [credentials.publish-creds] CI context rejects placeholder credential values"));
        assertTrue(output.contains("PUBLISH_USERNAME, PUBLISH_ACCESS_TOKEN"));
        assertTrue(output.contains("publish repository `company-releases`"));
        assertFalse(output.contains("dummy"));
        assertFalse(output.contains("read.only"));
    }

}
