package sh.zolt.resolve.materialization.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.maven.repository.RepositoryConfigurationIdentity;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
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

    /**
     * Design §8.5 makes repository lookup order authored policy and fetching is first-match-wins, so
     * two projects that declare the same repositories in opposite order can select different bytes for
     * the same coordinate. Sharing one persistent cache scope between them would let the first
     * project's choice answer for the second, so order is part of the identity.
     */
    @Test
    void sameRepositorySetDifferentOrderHasDifferentIdentity() {
        assertNotEquals(
                identity(orderedRepositories("[\"alpha\", \"zeta\"]")),
                identity(orderedRepositories("[\"zeta\", \"alpha\"]")));
    }

    /** Declaration order is not lookup order: §8.5 derives the default from sorted IDs. */
    @Test
    void sameOrderedRepositoryConfigurationHasStableIdentity() {
        assertEquals(
                identity("""
                        [repositories]
                        central = false

                        [repositories.alpha]
                        url = "https://repo.example/alpha"

                        [repositories.zeta]
                        url = "https://repo.example/zeta"
                        """),
                identity("""
                        [repositories]
                        central = false

                        [repositories.zeta]
                        url = "https://repo.example/zeta"

                        [repositories.alpha]
                        url = "https://repo.example/alpha"
                        """));
        assertEquals(
                """
                repository\t0\talpha\thttps://repo.example/alpha\t
                repository\t1\tzeta\thttps://repo.example/zeta\t""",
                identity(orderedRepositories("[\"alpha\", \"zeta\"]")));
    }

    /**
     * The identity is a cache key that outlives the process, so it names credential environment
     * variables and never reads them. Pinning the whole value is the strongest statement of that:
     * nothing beyond repository sequence, ID, URL, credential reference, and authentication form is
     * in it at all.
     */
    @Test
    void credentialSecretValuesDoNotEnterIdentity() {
        assertEquals(
                """
                repository\t0\tcompany\thttps://repo.example/company\tcompany-artifactory
                repository\t1\tinternal\thttps://repo.example/internal\tinternal-registry
                credential\tcompany-artifactory\tbasic\tREPOSITORY_USERNAME\tREPOSITORY_PASSWORD
                credential\tinternal-registry\ttoken\tREPOSITORY_TOKEN""",
                identity("""
                        [repositories]
                        central = false

                        [repositories.company]
                        url = "https://repo.example/company"
                        credentials = "company-artifactory"

                        [repositories.internal]
                        url = "https://repo.example/internal"
                        credentials = "internal-registry"

                        [credentials.company-artifactory]
                        usernameEnv = "REPOSITORY_USERNAME"
                        passwordEnv = "REPOSITORY_PASSWORD"

                        [credentials.internal-registry]
                        tokenEnv = "REPOSITORY_TOKEN"
                        """));
    }

    private static String orderedRepositories(String order) {
        return """
                [repositories]
                central = false
                order = %s

                [repositories.alpha]
                url = "https://repo.example/alpha"

                [repositories.zeta]
                url = "https://repo.example/zeta"
                """.formatted(order);
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

                        [repositories.internal]
                        url = "https://repo.example/internal"
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
                        central = false

                        [repositories.company]
                        url = "https://repo.example/company"
                        """),
                identity("""
                        [repositories]
                        central = false

                        [repositories.company]
                        url = "https://repo.example/company"
                        credentials = "company-artifactory"

                        [credentials.company-artifactory]
                        tokenEnv = "REPOSITORY_TOKEN"
                        """));
    }

    @Test
    void namesCredentialEnvironmentVariablesRatherThanTheirValues() {
        String identity = identity("""
                [repositories]
                central = false

                [repositories.company]
                url = "https://repo.example/company"
                credentials = "company-artifactory"

                [credentials.company-artifactory]
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
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                %s
                """.formatted(repositoryToml));
    }
}
