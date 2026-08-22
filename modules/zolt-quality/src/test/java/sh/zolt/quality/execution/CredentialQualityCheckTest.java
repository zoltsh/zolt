package sh.zolt.quality.execution;

import sh.zolt.publish.ManifestPublishSettingsLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.ProjectConfig;
import sh.zolt.project.RepositorySettings;
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

final class CredentialQualityCheckTest {
    private final ManifestProjectConfigLoader manifestLoader = new ManifestProjectConfigLoader();

    @TempDir
    private Path tempDir;

    @Test
    void credentialChecksSkipOutsideCiContext() throws IOException {
        Path projectDir = tempDir.resolve("local-context");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
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
        ProjectConfig config = manifestLoader.load(projectDir.resolve("zolt.toml"));
        CredentialQualityCheck check = new CredentialQualityCheck(new ManifestPublishSettingsLoader(), Map.<String, String>of()::get);

        assertEquals(List.of(), check.checkRepositoryCredentials(Optional.empty(), config, QualityCheckContext.LOCAL));
        assertEquals(List.of(), check.checkPublishCredentials(Optional.empty(), projectDir, config, QualityCheckContext.LOCAL));
        assertEquals(List.of(), check.checkResourceTokens(Optional.empty(), config, QualityCheckContext.LOCAL));
    }

    @Test
    void repositoryCredentialCheckReportsMissingCredentialMetadata() {
        ProjectConfig parsed = manifestLoader.load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.company]
                url = "https://repo.example.test/maven"
                """);
        ProjectConfig config = withRepositorySettings(parsed, Map.of(
                "company",
                new RepositorySettings(
                        "company",
                        "https://repo.example.test/maven",
                        Optional.of("company-artifactory"))));
        CredentialQualityCheck check = new CredentialQualityCheck(new ManifestPublishSettingsLoader(), Map.<String, String>of()::get);

        QualityCheckResult result = check.checkRepositoryCredentials(
                Optional.empty(),
                config,
                QualityCheckContext.CI).getFirst();

        assertEquals("[credentials.company-artifactory]", result.subject());
        assertEquals("Repository `company` references missing credential metadata.", result.message());
        assertEquals(
                "Define [credentials.company-artifactory] with environment variable names, not secret values.",
                result.nextStep());
    }

    @Test
    void repositoryCredentialCheckReportsMissingEnvironmentVariableByNameOnly() {
        ProjectConfig config = manifestLoader.load("""
                [project]
                name = "demo"
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
        CredentialQualityCheck check = new CredentialQualityCheck(
                new ManifestPublishSettingsLoader(),
                Map.of("ARTIFACTORY_USERNAME", "ci-user")::get);

        QualityCheckResult result = check.checkRepositoryCredentials(
                Optional.empty(),
                config,
                QualityCheckContext.CI).getFirst();

        assertEquals("[credentials.company-artifactory]", result.subject());
        assertEquals(
                "CI context requires environment variable ARTIFACTORY_ACCESS_TOKEN for repository `company` credentials `company-artifactory` before resolve/build work starts.",
                result.message());
        assertEquals(
                "Set the named CI secret and rerun `zolt check --context ci`. Secret values are never printed.",
                result.nextStep());
        assertFalse(result.message().contains("ci-user"));
    }

    @Test
    void repositoryCredentialCheckReportsCredentialedRepositorySummary() {
        ProjectConfig config = manifestLoader.load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.alpha]
                url = "https://alpha.example.test/maven"
                credentials = "alpha-creds"

                [repositories.beta]
                url = "https://beta.example.test/maven"
                credentials = "beta-creds"

                [credentials.alpha-creds]
                usernameEnv = "ALPHA_USERNAME"
                passwordEnv = "ALPHA_TOKEN"

                [credentials.beta-creds]
                usernameEnv = "BETA_USERNAME"
                passwordEnv = "BETA_TOKEN"
                """);
        CredentialQualityCheck check = new CredentialQualityCheck(new ManifestPublishSettingsLoader(), Map.of(
                        "ALPHA_USERNAME", "alpha-user",
                        "ALPHA_TOKEN", "alpha-token",
                        "BETA_USERNAME", "beta-user",
                        "BETA_TOKEN", "beta-token")
                ::get);

        QualityCheckResult result = check.checkRepositoryCredentials(
                Optional.empty(),
                config,
                QualityCheckContext.CI).getFirst();

        assertEquals("repository-credentials", result.subject());
        assertEquals("CI credential preflight passed for 2 credentialed repositories.", result.message());
        assertEquals("", result.nextStep());
    }

    @Test
    void repositoryCredentialCheckReportsSingleCredentialedRepositorySummary() {
        ProjectConfig config = manifestLoader.load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.company]
                url = "https://repo.example.test/maven"
                credentials = "company-creds"

                [credentials.company-creds]
                usernameEnv = "COMPANY_USERNAME"
                passwordEnv = "COMPANY_TOKEN"
                """);
        CredentialQualityCheck check = new CredentialQualityCheck(new ManifestPublishSettingsLoader(), Map.of(
                        "COMPANY_USERNAME", "ci-user",
                        "COMPANY_TOKEN", "ci-token")
                ::get);

        QualityCheckResult result = check.checkRepositoryCredentials(
                Optional.empty(),
                config,
                QualityCheckContext.CI).getFirst();

        assertEquals("repository-credentials", result.subject());
        assertEquals("CI credential preflight passed for 1 credentialed repository.", result.message());
        assertEquals("", result.nextStep());
    }

    @Test
    void resourceTokenCheckReportsMissingEnvTokenByNameOnly() {
        ProjectConfig config = manifestLoader.load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [resources.filter]
                include = ["**/*.properties"]

                [resources.tokens]
                api-token = { env = "API_TOKEN" }
                literal-name = { value = "demo" }
                """);
        CredentialQualityCheck check = new CredentialQualityCheck(new ManifestPublishSettingsLoader(), Map.<String, String>of()::get);

        QualityCheckResult result = check.checkResourceTokens(
                Optional.empty(),
                config,
                QualityCheckContext.CI).getFirst();

        assertEquals("[resources.tokens.api-token]", result.subject());
        assertEquals(
                "CI context requires environment variable API_TOKEN for resource token `api-token` before resource copying.",
                result.message());
        assertEquals(
                "Set the named CI variable or change [resources.tokens].api-token to an explicit non-secret value/project source. Values are never printed.",
                result.nextStep());
    }

    @Test
    void resourceTokenCheckReportsDeterministicSourceCounts() {
        ProjectConfig config = manifestLoader.load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [resources.filter]
                targets = ["main", "test"]
                include = ["**/*.properties"]

                [resources.tokens]
                build-number = { env = "BUILD_NUMBER" }
                literal-name = { value = "demo" }
                project-version = { project = "version" }
                """);
        CredentialQualityCheck check = new CredentialQualityCheck(
                new ManifestPublishSettingsLoader(),
                Map.of("BUILD_NUMBER", "42")::get);

        QualityCheckResult result = check.checkResourceTokens(
                Optional.of("apps/api"),
                config,
                QualityCheckContext.CI).getFirst();

        assertEquals(Optional.of("apps/api"), result.member());
        assertEquals("resource-token-inputs", result.subject());
        assertEquals("CI resource token preflight passed for 3 tokens: env=1, project=1, literal=1.", result.message());
    }

    /**
     * A repository URL carrying user information is rejected at parse time now, so this branch is
     * defense in depth: the check must still fail closed from a directly constructed
     * {@link RepositorySettings}, and no diagnostic may echo the credential it found.
     */
    @Test
    void repositoryUrlCredentialFailuresFailClosedWithoutEchoingTheSecret() {
        ProjectConfig parsed = manifestLoader.load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.company]
                url = "https://repo.example.test/maven"
                """);
        CredentialQualityCheck check = new CredentialQualityCheck(
                new ManifestPublishSettingsLoader(), Map.<String, String>of()::get);

        for (Leak leak : List.of(
                new Leak(
                        "https://repo-user:super-secret@repo.example.test/maven",
                        "CI context rejects embedded credentials in repository `company` URL."),
                new Leak(
                        "https://repo-user:super-secret@repo.example.test/ma ven",
                        "Repository `company` URL is not a valid URI."))) {
            ProjectConfig config = withRepositorySettings(parsed, Map.of(
                    "company",
                    new RepositorySettings("company", leak.url(), Optional.of("company-creds"))));

            QualityCheckResult result = check.checkRepositoryCredentials(
                    Optional.empty(), config, QualityCheckContext.CI).getFirst();

            assertEquals("[repositories.company]", result.subject(), leak.url());
            assertEquals(leak.message(), result.message(), leak.url());
            assertDoesNotLeakSecret(result);
            assertFalse(result.message().contains(leak.url()), result.message());
            assertFalse(result.nextStep().contains(leak.url()), result.nextStep());
        }
    }

    private record Leak(String url, String message) {
    }

    private static ProjectConfig withRepositorySettings(
            ProjectConfig config,
            Map<String, RepositorySettings> repositorySettings) {
        return new ProjectConfig(
                config.project(),
                config.repositories(),
                repositorySettings,
                config.repositoryCredentials(),
                config.versionAliases(),
                config.platforms(),
                config.apiDependencies(),
                config.managedApiDependencies(),
                config.workspaceApiDependencies(),
                config.dependencies(),
                config.managedDependencies(),
                config.workspaceDependencies(),
                config.runtimeDependencies(),
                config.managedRuntimeDependencies(),
                config.providedDependencies(),
                config.managedProvidedDependencies(),
                config.devDependencies(),
                config.managedDevDependencies(),
                config.testDependencies(),
                config.managedTestDependencies(),
                config.workspaceTestDependencies(),
                config.annotationProcessors(),
                config.managedAnnotationProcessors(),
                config.workspaceAnnotationProcessors(),
                config.testAnnotationProcessors(),
                config.managedTestAnnotationProcessors(),
                config.workspaceTestAnnotationProcessors(),
                config.dependencyPolicy(),
                config.build(),
                config.nativeSettings(),
                config.compilerSettings(),
                config.packageSettings(),
                config.frameworkSettings(),
                config.dependencyMetadata());
    }

    private static void assertDoesNotLeakSecret(QualityCheckResult result) {
        String rendered = result.message() + "\n" + result.nextStep();
        assertFalse(rendered.contains("repo-user"));
        assertFalse(rendered.contains("publish-user"));
        assertFalse(rendered.contains("super-secret"));
    }

}
