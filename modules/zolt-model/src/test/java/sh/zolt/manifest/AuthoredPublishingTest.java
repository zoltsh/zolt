package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class AuthoredPublishingTest {
    private static final LocalId RELEASES = new LocalId("company-releases");
    private static final LocalId SNAPSHOTS = new LocalId("company-snapshots");

    @Test
    void retainsAuthoredSectionsAndSortsNamedRepositories() {
        LocalId credential = new LocalId("company");
        LinkedHashMap<LocalId, AuthoredPublicationRepository> source = new LinkedHashMap<>();
        source.put(SNAPSHOTS, repository("https://repo.example.com/snapshots", credential));
        source.put(RELEASES, repository("https://repo.example.com/releases", credential));

        AuthoredPublishing publishing = new AuthoredPublishing(
                Optional.of(new AuthoredPublicationRoutes(
                        Optional.of(RELEASES), Optional.of(SNAPSHOTS))),
                source,
                Optional.of(new AuthoredPublicationSigning(
                        AuthoredPublicationSigning.Method.GPG,
                        Optional.of("3AB1C2D3E4F5A6B7"),
                        Optional.of(new EnvironmentVariableName("ZOLT_SIGNING_PASSPHRASE")))),
                Optional.of(new AuthoredCentralPublishing(
                        new EnvironmentVariableName("ZOLT_CENTRAL_TOKEN"),
                        AuthoredCentralPublishing.Mode.AUTOMATIC,
                        Optional.of("example-library-1.0.0"),
                        Optional.of(new RepositoryUrl("https://central.sonatype.com")))));
        source.clear();

        assertEquals(List.of(RELEASES, SNAPSHOTS),
                new ArrayList<>(publishing.repositories().keySet()));
        assertEquals(List.of(credential), publishing.credentialReferences());
        assertEquals("gpg", publishing.signing().orElseThrow().method().configValue());
        assertEquals("automatic", publishing.central().orElseThrow().mode().configValue());
        assertEquals(RELEASES, publishing.routes().orElseThrow().release().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> publishing.repositories().clear());
    }

    @Test
    void preservesCompletePublicationOmission() {
        AuthoredPublishing publishing = AuthoredPublishing.empty();

        assertTrue(publishing.routes().isEmpty());
        assertTrue(publishing.repositories().isEmpty());
        assertTrue(publishing.signing().isEmpty());
        assertTrue(publishing.central().isEmpty());
        assertTrue(publishing.credentialReferences().isEmpty());
    }

    @Test
    void requiresEveryAuthoredRouteToNameADefinedRepository() {
        AuthoredPublicationRoutes routes = new AuthoredPublicationRoutes(
                Optional.of(RELEASES), Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> new AuthoredPublishing(
                Optional.of(routes), Map.of(), Optional.empty(), Optional.empty()));
        assertEquals(
                RELEASES,
                new AuthoredPublishing(
                                Optional.of(routes),
                                Map.of(RELEASES, AuthoredPublicationRepository.unauthenticated(
                                        new RepositoryUrl("https://repo.example.com/releases"))),
                                Optional.empty(),
                                Optional.empty())
                        .routes().orElseThrow().release().orElseThrow());
    }

    @Test
    void rejectsEmptySingletonsAndBlankExternalMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new AuthoredPublicationRoutes(
                Optional.empty(), Optional.empty()));
        assertThrows(NullPointerException.class, () -> new AuthoredCentralPublishing(
                null,
                AuthoredCentralPublishing.Mode.MANUAL,
                Optional.empty(),
                Optional.empty()));
        assertThrows(NullPointerException.class, () -> new AuthoredCentralPublishing(
                new EnvironmentVariableName("ZOLT_CENTRAL_TOKEN"),
                null,
                Optional.empty(),
                Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredCentralPublishing(
                new EnvironmentVariableName("ZOLT_CENTRAL_TOKEN"),
                AuthoredCentralPublishing.Mode.MANUAL,
                Optional.of("  "),
                Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredPublicationSigning(
                AuthoredPublicationSigning.Method.GPG,
                Optional.of("\t"),
                Optional.empty()));
    }

    @Test
    void rejectsCaseCollidingDirectPublicationEnvironmentReferences() {
        AuthoredPublicationSigning signing = new AuthoredPublicationSigning(
                AuthoredPublicationSigning.Method.GPG,
                Optional.empty(),
                Optional.of(new EnvironmentVariableName("PUBLISH_TOKEN")));
        AuthoredCentralPublishing central = new AuthoredCentralPublishing(
                new EnvironmentVariableName("publish_token"),
                AuthoredCentralPublishing.Mode.MANUAL,
                Optional.empty(),
                Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> new AuthoredPublishing(
                Optional.empty(), Map.of(), Optional.of(signing), Optional.of(central)));
    }

    @Test
    void centralModesAndSigningMethodAreClosedTokens() {
        assertEquals("gpg", AuthoredPublicationSigning.Method.GPG.configValue());
        assertEquals("manual", AuthoredCentralPublishing.Mode.MANUAL.configValue());
        assertEquals("automatic", AuthoredCentralPublishing.Mode.AUTOMATIC.configValue());
    }

    private static AuthoredPublicationRepository repository(
            String url,
            LocalId credential) {
        return new AuthoredPublicationRepository(
                new RepositoryUrl(url), Optional.of(credential));
    }
}
