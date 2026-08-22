package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.RepositoryCredential;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.toml.ZoltConfigException;

final class ManifestCredentialsDecoderTest {
    @Test
    void decodesBearerAndBasicFormsIntoTheModelSortedMap() {
        AuthoredCredentials credentials = decode("""
                [credentials.github]
                tokenEnv = "GITHUB_TOKEN"

                [credentials.company]
                usernameEnv = "MAVEN_USERNAME"
                passwordEnv = "MAVEN_PASSWORD"
                """);

        assertEquals(
                List.of(new LocalId("company"), new LocalId("github")),
                List.copyOf(credentials.entries().keySet()));
        assertInstanceOf(
                RepositoryCredential.Basic.class,
                credentials.entries().get(new LocalId("company")));
        assertInstanceOf(
                RepositoryCredential.BearerToken.class,
                credentials.entries().get(new LocalId("github")));
    }

    @ParameterizedTest
    @MethodSource("invalidForms")
    void rejectsEveryPartialOrMixedCredentialForm(String fields) {
        assertFailure(
                "[credentials.company]\n" + fields,
                "Invalid manifest section `[credentials.company]`: Credential must declare exactly");
    }

    static Stream<Arguments> invalidForms() {
        return Stream.of(
                Arguments.of("usernameEnv = \"USER\"\n"),
                Arguments.of("passwordEnv = \"PASSWORD\"\n"),
                Arguments.of("tokenEnv = \"TOKEN\"\nusernameEnv = \"USER\"\n"),
                Arguments.of("tokenEnv = \"TOKEN\"\npasswordEnv = \"PASSWORD\"\n"),
                Arguments.of(
                        "tokenEnv = \"TOKEN\"\nusernameEnv = \"USER\"\npasswordEnv = \"PASSWORD\"\n"));
    }

    @Test
    void anchorsEnvironmentGrammarFailuresToTheirConcreteFields() {
        assertFailure("""
                [credentials.company]
                tokenEnv = "BAD-NAME"
                """, "Invalid value for `credentials.company.tokenEnv`");
    }

    @Test
    void rejectsCaseOnlyEnvironmentCollisionsAtTheCollectionAggregate() {
        assertFailure("""
                [credentials.alpha]
                tokenEnv = "MAVEN_TOKEN"

                [credentials.beta]
                usernameEnv = "maven_token"
                passwordEnv = "MAVEN_PASSWORD"
                """, "Invalid manifest section `[credentials]`: Environment-variable names "
                        + "`MAVEN_TOKEN` and `maven_token` differ only by ASCII case");
    }

    @Test
    void permitsExactEnvironmentNameReuseAcrossForms() {
        AuthoredCredentials credentials = decode("""
                [credentials.alpha]
                tokenEnv = "SHARED_SECRET"

                [credentials.beta]
                usernameEnv = "SHARED_SECRET"
                passwordEnv = "SHARED_SECRET"
                """);

        assertEquals(2, credentials.entries().size());
    }

    @Test
    void leavesCredentialIdsAndEmptyNamedRowsToShapeValidation() {
        assertFailure("""
                [credentials.Bad_Id]
                tokenEnv = "TOKEN"
                """, "Invalid dynamic key `Bad_Id`");
        assertFailure("[credentials.company]\n", "Manifest table `[credentials.company]` must not be empty");
    }

    private static AuthoredCredentials decode(String source) {
        return new ManifestCredentialsDecoder()
                .decode(ManifestSemanticTestSupport.index(source))
                .orElseThrow();
    }

    private static void assertFailure(String source, String expected) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
