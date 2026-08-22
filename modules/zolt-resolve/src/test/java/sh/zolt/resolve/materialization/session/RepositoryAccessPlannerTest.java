package sh.zolt.resolve.materialization.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.maven.repository.RepositoryAccess;
import sh.zolt.maven.repository.RepositoryAccessException;
import sh.zolt.maven.repository.RepositoryAccessPlanner;
import sh.zolt.maven.repository.RepositoryAuthentication;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.RepositoryCredentialSettings;
import sh.zolt.project.RepositorySettings;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RepositoryAccessPlannerTest {
    @Test
    void plansRepositoriesByStableIdOrder() {
        List<RepositoryAccess> access = new RepositoryAccessPlanner().plan(config("""
                [repositories]
                central = false

                [repositories.zeta]
                url = "https://repo.example/zeta"

                [repositories.alpha]
                url = "https://repo.example/alpha"
                """));

        assertEquals("https://repo.example/alpha", access.get(0).uri().toString());
        assertEquals("https://repo.example/zeta", access.get(1).uri().toString());
        assertTrue(access.get(0).authentication().isEmpty());
        assertTrue(access.get(1).authentication().isEmpty());
    }

    /**
     * Design §8.5: fetching is first-match-wins, so an authored {@code order} has to survive to the
     * plan verbatim. Re-sorting it here would let Maven Central answer for a coordinate a private
     * repository was declared to serve first.
     */
    @Test
    void queriesRepositoriesInTheAuthoredLookupOrder() {
        List<RepositoryAccess> access = new RepositoryAccessPlanner().plan(config("""
                [repositories]
                order = ["snapshots", "releases", "central"]

                [repositories.releases]
                url = "https://repo.example/releases"

                [repositories.snapshots]
                url = "https://repo.example/snapshots"
                """));

        assertEquals(
                List.of("snapshots", "releases", "central"),
                access.stream().map(RepositoryAccess::id).toList());
    }

    /** Design §8.2/§8.5: adding a private repository shadows Central rather than trailing it. */
    @Test
    void placesCentralLastWhenCustomRepositoriesExist() {
        List<RepositoryAccess> access = new RepositoryAccessPlanner().plan(config("""
                [repositories.acme]
                url = "https://repo.example/acme"

                [repositories.zeta]
                url = "https://repo.example/zeta"
                """));

        assertEquals(List.of("acme", "zeta", "central"), access.stream().map(RepositoryAccess::id).toList());
        assertEquals(ProjectConfig.MAVEN_CENTRAL, access.getLast().uri().toString());
    }

    /**
     * Design §8.3: {@code central = false} with no custom repository is a deliberate empty universe.
     * The first request for an external artifact fails with an actionable diagnostic instead of
     * silently falling back to the repository the manifest disabled.
     */
    @Test
    void reportsTheEmptyRepositoryUniverseInsteadOfFallingBackToCentral() {
        RepositoryAccessException exception = assertThrows(
                RepositoryAccessException.class,
                () -> new RepositoryAccessPlanner().plan(config("""
                        [repositories]
                        central = false
                        """)));

        assertTrue(exception.getMessage().contains("No repositories are configured in zolt.toml."));
        assertTrue(exception.actionableError().remediation()
                .contains("[repositories].central = false leaves no repository to query"));
    }

    @Test
    void resolvesCredentialedRepositoryFromEnvironment() {
        RepositoryAccessPlanner planner = new RepositoryAccessPlanner(Map.of(
                "REPOSITORY_USERNAME",
                "user",
                "REPOSITORY_PASSWORD",
                "secret")::get);

        RepositoryAccess access = planner.plan(config("""
                [repositories]
                central = false

                [repositories.company]
                url = "https://repo.example/company"
                credentials = "company-artifactory"

                [credentials.company-artifactory]
                usernameEnv = "REPOSITORY_USERNAME"
                passwordEnv = "REPOSITORY_PASSWORD"
                """)).getFirst();

        RepositoryAuthentication authentication = access.authentication().orElseThrow();
        assertEquals("https://repo.example/company", access.uri().toString());
        assertEquals(
                "Basic " + Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8)),
                authentication.authorizationHeaderValue());
    }

    @Test
    void resolvesBearerTokenCredentialFromEnvironment() {
        RepositoryAccessPlanner planner = new RepositoryAccessPlanner(Map.of("REPOSITORY_TOKEN", "pat-xyz")::get);

        RepositoryAccess access = planner.plan(config("""
                [repositories]
                central = false

                [repositories.company]
                url = "https://repo.example/company"
                credentials = "company-artifactory"

                [credentials.company-artifactory]
                tokenEnv = "REPOSITORY_TOKEN"
                """)).getFirst();

        assertEquals("Bearer pat-xyz", access.authentication().orElseThrow().authorizationHeaderValue());
    }

    @Test
    void rejectsRepositoryUrlUserinfoBeforeResolution() {
        RepositoryAccessException exception = assertThrows(
                RepositoryAccessException.class,
                () -> new RepositoryAccessPlanner().plan(withRepositoryUrl(
                        config("""
                                [repositories]
                                central = false

                                [repositories.company]
                                url = "https://repo.example/company"
                                """),
                        "company",
                        "https://user:super-secret@repo.example/company")));

        assertTrue(exception.getMessage().contains("Repository `company` URL contains embedded credentials"));
        assertTrue(exception.getMessage().contains("Move credentials to [credentials] environment references"));
        assertTrue(!exception.getMessage().contains("user:super-secret"));
        assertTrue(!exception.getMessage().contains("super-secret"));
    }

    @Test
    void rejectsCredentialedRemoteHttpRepository() {
        RepositoryAccessPlanner planner = new RepositoryAccessPlanner(Map.of(
                "REPOSITORY_USERNAME",
                "user",
                "REPOSITORY_PASSWORD",
                "secret")::get);

        RepositoryAccessException exception = assertThrows(
                RepositoryAccessException.class,
                () -> planner.plan(withRepositoryUrl(
                        config("""
                                [repositories]
                                central = false

                                [repositories.company]
                                url = "https://repo.example/company"
                                credentials = "company-artifactory"

                                [credentials.company-artifactory]
                                usernameEnv = "REPOSITORY_USERNAME"
                                passwordEnv = "REPOSITORY_PASSWORD"
                                """),
                        "company",
                        "http://repo.example/company")));

        assertTrue(exception.getMessage().contains("Repository `company` uses credentials with an insecure remote repository URL"));
        assertTrue(exception.getMessage().contains("Credentialed remote repositories require HTTPS"));
    }

    @Test
    void rejectsNonLocalHttpRepository() {
        RepositoryAccessException exception = assertThrows(
                RepositoryAccessException.class,
                () -> new RepositoryAccessPlanner().plan(withRepositoryUrl(
                        config("""
                                [repositories]
                                central = false

                                [repositories.company]
                                url = "https://repo.example/company"
                                """),
                        "company",
                        "http://repo.example/company")));

        assertTrue(exception.getMessage().contains("Repository `company` uses non-local HTTP"));
        assertTrue(exception.getMessage().contains("plain HTTP is allowed only for localhost or loopback"));
    }

    @Test
    void allowsLoopbackHttpRepositoryForLocalDevelopment() {
        List<RepositoryAccess> access = new RepositoryAccessPlanner().plan(config("""
                [repositories]
                central = false

                [repositories.local]
                url = "http://127.0.0.1:18080/maven2"
                """));

        assertEquals("http://127.0.0.1:18080/maven2", access.getFirst().uri().toString());
    }

    @Test
    void reportsMissingCredentialDefinition() {
        ProjectConfig config = withoutRepositoryCredentials(config("""
                [repositories]
                central = false

                [repositories.company]
                url = "https://repo.example/company"
                credentials = "company-artifactory"

                [credentials.company-artifactory]
                usernameEnv = "REPOSITORY_USERNAME"
                passwordEnv = "REPOSITORY_PASSWORD"
                """));

        RepositoryAccessException exception = assertThrows(
                RepositoryAccessException.class,
                () -> new RepositoryAccessPlanner().plan(config));

        assertTrue(exception.getMessage().contains("Repository `company` references credentials `company-artifactory`"));
        assertTrue(exception.getMessage().contains("[credentials.company-artifactory] is not defined"));
    }

    @Test
    void reportsMissingCredentialEnvironmentWithoutSecretValues() {
        RepositoryAccessPlanner planner = new RepositoryAccessPlanner(Map.of(
                "REPOSITORY_USERNAME",
                "user")::get);

        RepositoryAccessException exception = assertThrows(
                RepositoryAccessException.class,
                () -> planner.plan(config("""
                        [repositories]
                        central = false

                        [repositories.company]
                        url = "https://repo.example/company"
                        credentials = "company-artifactory"

                        [credentials.company-artifactory]
                        usernameEnv = "REPOSITORY_USERNAME"
                        passwordEnv = "REPOSITORY_PASSWORD"
                        """)));

        assertTrue(exception.getMessage().contains("Repository `company` requires credentials `company-artifactory`"));
        assertTrue(exception.getMessage().contains("REPOSITORY_PASSWORD"));
        assertTrue(exception.getMessage().contains("Secret values are never written to zolt.lock or command output."));
    }

    private static ProjectConfig config(String repositoryToml) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                %s
                """.formatted(repositoryToml));
    }

    /**
     * Replaces one repository URL after parsing. The final manifest language rejects these URLs at
     * parse time (design §8.4), so the planner's own defensive checks need a non-manifest input.
     */
    private static ProjectConfig withRepositoryUrl(ProjectConfig config, String id, String url) {
        Map<String, String> repositories = new java.util.LinkedHashMap<>(config.repositories());
        repositories.put(id, url);
        Map<String, RepositorySettings> settings =
                new java.util.LinkedHashMap<>(config.repositorySettings());
        RepositorySettings existing = settings.get(id);
        settings.put(id, new RepositorySettings(id, url, existing.credentials()));
        return rebuild(config, repositories, settings, config.repositoryCredentials());
    }

    private static ProjectConfig withoutRepositoryCredentials(ProjectConfig config) {
        return rebuild(config, config.repositories(), config.repositorySettings(), Map.of());
    }

    private static ProjectConfig rebuild(
            ProjectConfig config,
            Map<String, String> repositories,
            Map<String, RepositorySettings> repositorySettings,
            Map<String, RepositoryCredentialSettings> repositoryCredentials) {
        return new ProjectConfig(
                config.project(),
                repositories,
                repositorySettings,
                repositoryCredentials,
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
}
