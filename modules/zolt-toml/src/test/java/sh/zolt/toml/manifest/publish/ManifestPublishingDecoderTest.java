package sh.zolt.toml.manifest.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestPublishingTestSupport.decodePublishing;
import static sh.zolt.toml.manifest.ManifestPublishingTestSupport.decodePublishingWithNullIndex;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredCentralPublishing;
import sh.zolt.manifest.authored.AuthoredPublicationSigning;
import sh.zolt.manifest.authored.AuthoredPublishing;
import sh.zolt.toml.ZoltConfigException;

final class ManifestPublishingDecoderTest {
    @Test
    void distinguishesTotalOmissionFromExplicitEmptyRepositoryPresence() {
        assertTrue(decodePublishing("").isEmpty());
        for (String source : List.of(
                "[publish.repositories]\n",
                "publish = { repositories = {} }\n")) {
            assertEquals(AuthoredPublishing.empty(), decode(source));
        }
    }

    @Test
    void composesEveryDomainWithSortedImmutableRepositoriesAndNoDefaults() {
        AuthoredPublishing publishing = decode("""
                [publish]
                release = "alpha"
                snapshot = "zeta"

                [publish.repositories.zeta]
                url = "https://zeta.example.test/maven"
                credentials = "company"

                [publish.repositories.alpha]
                url = "https://alpha.example.test/maven"

                [publish.signing]
                method = "gpg"
                keyId = "release-key"
                passphraseEnv = "SIGNING_PASSPHRASE"

                [publish.central]
                tokenEnv = "CENTRAL_TOKEN"
                mode = "automatic"
                name = "Zolt Release"
                url = "https://central.example.test/api/"
                """);

        assertEquals(id("alpha"), publishing.routes().orElseThrow().release().orElseThrow());
        assertEquals(id("zeta"), publishing.routes().orElseThrow().snapshot().orElseThrow());
        assertEquals(
                List.of("alpha", "zeta"),
                publishing.repositories().keySet().stream().map(LocalId::value).toList());
        assertTrue(publishing.repositories().get(id("alpha")).credentials().isEmpty());
        assertEquals(
                Optional.of(id("company")),
                publishing.repositories().get(id("zeta")).credentials());
        assertEquals(
                AuthoredPublicationSigning.Method.GPG,
                publishing.signing().orElseThrow().method());
        assertEquals(
                AuthoredCentralPublishing.Mode.AUTOMATIC,
                publishing.central().orElseThrow().mode());
        assertThrows(UnsupportedOperationException.class, publishing.repositories()::clear);
    }

    @Test
    void anchorsUndefinedReleaseThenSnapshotRoutesToTheirExactFields() {
        assertSemanticFailure(
                "publish.release = \"missing\"\n[publish.repositories]\n",
                "publish.release",
                "release route references undefined repository `missing`");
        assertSemanticFailure("""
                [publish]
                snapshot = "missing"
                release = "remote"
                [publish.repositories.remote]
                url = "https://repo.example.test/maven"
                """,
                "publish.snapshot",
                "snapshot route references undefined repository `missing`");
    }

    @Test
    void finishesRepositoryRowsBeforeRouteReferencesThenStopsBeforeLaterDomains() {
        assertSemanticFailure("""
                publish.release = "missing"
                [publish.repositories.remote]
                url = "relative"
                """,
                "publish.repositories.remote.url",
                "Invalid repository URL");
        assertSemanticFailure("""
                [publish]
                release = "missing"
                [publish.repositories]
                [publish.signing]
                method = "gpg"
                keyId = " "
                [publish.central]
                tokenEnv = "CENTRAL_TOKEN"
                mode = "manual"
                name = " "
                """,
                "publish.release",
                "release route references undefined repository");
    }

    @Test
    void anchorsEnvironmentCaseCollisionsToCentralTokenBeforeOptionalFields() {
        ZoltConfigException failure = assertSemanticFailure("""
                [publish.signing]
                method = "gpg"
                passphraseEnv = "TOKEN"
                [publish.central]
                tokenEnv = "token"
                mode = "manual"
                name = " "
                url = "relative"
                """,
                "publish.central.tokenEnv",
                "differ only by ASCII case");
        assertTrue(failure.getMessage().contains("`TOKEN` and `token`"), failure.getMessage());
    }

    @Test
    void requiresCentralModeBeforeCollisionsAndAllowsExactEnvironmentReuse() {
        ZoltConfigException missing = assertThrows(
                ZoltConfigException.class,
                () -> decodePublishing("""
                        [publish.signing]
                        method = "gpg"
                        passphraseEnv = "TOKEN"
                        [publish.central]
                        tokenEnv = "token"
                        name = " "
                        """));
        assertTrue(missing.getMessage().contains("publish.central.mode"), missing.getMessage());
        assertNull(missing.getCause());

        AuthoredPublishing publishing = decode("""
                [publish.signing]
                method = "gpg"
                passphraseEnv = "TOKEN"
                [publish.central]
                tokenEnv = "TOKEN"
                mode = "manual"
                """);
        assertEquals(
                publishing.signing().orElseThrow().passphraseEnvironment().orElseThrow(),
                publishing.central().orElseThrow().tokenEnvironment());
    }

    @Test
    void leavesCredentialExistenceAndOperationalReadinessForLaterComposition() {
        AuthoredPublishing publishing = decode("""
                [publish.repositories.remote]
                url = "https://repo.example.test/maven"
                credentials = "missing"
                [publish.central]
                tokenEnv = "CENTRAL_TOKEN"
                mode = "manual"
                """);

        assertEquals(Optional.of(id("missing")), publishing.repositories().get(id("remote")).credentials());
        assertTrue(publishing.central().orElseThrow().name().isEmpty());
        assertTrue(publishing.central().orElseThrow().url().isEmpty());
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decodePublishingWithNullIndex());
    }

    private static AuthoredPublishing decode(String source) {
        return decodePublishing(source).orElseThrow();
    }

    private static ZoltConfigException assertSemanticFailure(
            String source,
            String path,
            String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodePublishing(source));
        assertTrue(failure.getMessage().contains("`" + path + "`"), failure.getMessage());
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        return failure;
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }
}
