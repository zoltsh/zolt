package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RepositoryCredentialTest {
    @Test
    void modelsBearerAndBasicAsMutuallyExclusiveForms() {
        RepositoryCredential.BearerToken bearer = new RepositoryCredential.BearerToken(
                new EnvironmentVariableName("GITHUB_TOKEN"));
        RepositoryCredential.Basic basic = new RepositoryCredential.Basic(
                new EnvironmentVariableName("MAVEN_USERNAME"),
                new EnvironmentVariableName("MAVEN_PASSWORD"));

        assertEquals("GITHUB_TOKEN", bearer.tokenEnvironment().value());
        assertEquals("MAVEN_USERNAME", basic.usernameEnvironment().value());
        assertInstanceOf(RepositoryCredential.class, bearer);
        assertInstanceOf(RepositoryCredential.class, basic);
    }

    @Test
    void credentialFieldsAcceptOnlyPortableEnvironmentNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentVariableName("MAVEN-PASSWORD"));
        assertThrows(NullPointerException.class,
                () -> new RepositoryCredential.BearerToken(null));
        assertThrows(NullPointerException.class,
                () -> new RepositoryCredential.Basic(new EnvironmentVariableName("USER"), null));
    }

    @Test
    void sharedCredentialCollectionIsSortedCopiedAndImmutable() {
        LinkedHashMap<LocalId, RepositoryCredential> source = new LinkedHashMap<>();
        source.put(new LocalId("github"),
                new RepositoryCredential.BearerToken(new EnvironmentVariableName("GITHUB_TOKEN")));
        source.put(new LocalId("company"), new RepositoryCredential.Basic(
                new EnvironmentVariableName("MAVEN_USERNAME"),
                new EnvironmentVariableName("MAVEN_PASSWORD")));

        AuthoredCredentials credentials = new AuthoredCredentials(source);
        source.clear();

        assertEquals(List.of(new LocalId("company"), new LocalId("github")),
                List.copyOf(credentials.entries().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> credentials.entries().clear());
    }

    @Test
    void rejectsCaseOnlyEnvironmentNameCollisionsAcrossAllCredentialForms() {
        RepositoryCredential.Basic collidingBasic = new RepositoryCredential.Basic(
                new EnvironmentVariableName("MAVEN_USER"),
                new EnvironmentVariableName("maven_user"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredCredentials(Map.of(new LocalId("company"), collidingBasic)));

        RepositoryCredential.BearerToken upper = new RepositoryCredential.BearerToken(
                new EnvironmentVariableName("GITHUB_TOKEN"));
        RepositoryCredential.Basic lower = new RepositoryCredential.Basic(
                new EnvironmentVariableName("github_token"),
                new EnvironmentVariableName("GITHUB_PASSWORD"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredCredentials(Map.of(
                        new LocalId("github"), upper,
                        new LocalId("github-mirror"), lower)));
    }

    @Test
    void permitsExactEnvironmentNameReuse() {
        EnvironmentVariableName shared = new EnvironmentVariableName("MAVEN_TOKEN");
        AuthoredCredentials credentials = new AuthoredCredentials(Map.of(
                new LocalId("releases"), new RepositoryCredential.BearerToken(shared),
                new LocalId("snapshots"), new RepositoryCredential.BearerToken(shared),
                new LocalId("basic"), new RepositoryCredential.Basic(shared, shared)));

        assertEquals(3, credentials.entries().size());
    }
}
