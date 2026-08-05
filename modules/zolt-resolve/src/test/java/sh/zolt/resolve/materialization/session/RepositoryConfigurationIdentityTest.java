package sh.zolt.resolve.materialization.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.maven.repository.RepositoryConfigurationIdentity;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import org.junit.jupiter.api.Test;

final class RepositoryConfigurationIdentityTest {
    @Test
    void matchesForConfigurationsThatQueryTheSameRepositories() {
        assertEquals(
                identity("""
                        [repositories]
                        central = "https://repo.example/central"
                        """),
                identity("""
                        [repositories]
                        central = "https://repo.example/central"
                        """));
    }

    @Test
    void ignoresDeclarationOrderBecauseRepositoriesAreQueriedInIdOrder() {
        assertEquals(
                identity("""
                        [repositories]
                        alpha = "https://repo.example/alpha"
                        zeta = "https://repo.example/zeta"
                        """),
                identity("""
                        [repositories]
                        zeta = "https://repo.example/zeta"
                        alpha = "https://repo.example/alpha"
                        """));
    }

    @Test
    void differsWhenAMemberAddsARepositoryTheWorkspaceRootDoesNotDeclare() {
        assertNotEquals(
                identity("""
                        [repositories]
                        central = "https://repo.example/central"
                        """),
                identity("""
                        [repositories]
                        central = "https://repo.example/central"
                        internal = "https://repo.example/internal"
                        """));
    }

    @Test
    void differsWhenTheSameRepositoryIdPointsAtAnotherUrl() {
        assertNotEquals(
                identity("""
                        [repositories]
                        central = "https://repo.example/one"
                        """),
                identity("""
                        [repositories]
                        central = "https://repo.example/two"
                        """));
    }

    @Test
    void differsWhenARepositoryGainsCredentials() {
        assertNotEquals(
                identity("""
                        [repositories]
                        company = "https://repo.example/company"
                        """),
                identity("""
                        [repositories]
                        company = { url = "https://repo.example/company", credentials = "company-artifactory" }

                        [repositoryCredentials.company-artifactory]
                        tokenEnv = "REPOSITORY_TOKEN"
                        """));
    }

    @Test
    void namesCredentialEnvironmentVariablesRatherThanTheirValues() {
        String identity = identity("""
                [repositories]
                company = { url = "https://repo.example/company", credentials = "company-artifactory" }

                [repositoryCredentials.company-artifactory]
                usernameEnv = "REPOSITORY_USERNAME"
                passwordEnv = "REPOSITORY_PASSWORD"
                """);

        assertTrue(identity.contains("REPOSITORY_USERNAME"));
        assertTrue(identity.contains("REPOSITORY_PASSWORD"));
        assertFalse(identity.contains("://user"));
    }

    private static String identity(String repositoryToml) {
        return RepositoryConfigurationIdentity.of(config(repositoryToml));
    }

    private static ProjectConfig config(String repositoryToml) {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                %s
                """.formatted(repositoryToml));
    }
}
