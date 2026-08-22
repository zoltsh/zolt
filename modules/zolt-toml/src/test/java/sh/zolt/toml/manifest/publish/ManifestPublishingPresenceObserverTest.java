package sh.zolt.toml.manifest.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestPublishingTestSupport.decodePublishing;
import static sh.zolt.toml.manifest.ManifestPublishingTestSupport.decodePublishingWithNullIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.authored.AuthoredPublishing;
import sh.zolt.toml.ZoltConfigException;

final class ManifestPublishingPresenceObserverTest {
    @Test
    void observesCanonicalCumulativePublishingBoundariesInReverseSourceOrder() {
        ArrayList<AuthoredPublishing> observed = new ArrayList<>();

        AuthoredPublishing complete = decodePublishing("""
                [publish.central]
                url = "https://central.example.test/api/"
                name = "Zolt Release"
                mode = "automatic"
                tokenEnv = "CENTRAL_TOKEN"

                [publish.signing]
                passphraseEnv = "SIGNING_PASSPHRASE"
                keyId = "release-key"
                method = "gpg"

                [publish.repositories.remote]
                url = "https://repo.example.test/maven"

                [publish]
                release = "remote"
                """, observed::add).orElseThrow();

        assertEquals(3, observed.size());
        assertTrue(observed.get(0).routes().isPresent());
        assertEquals(1, observed.get(0).repositories().size());
        assertTrue(observed.get(0).signing().isEmpty());
        assertTrue(observed.get(0).central().isEmpty());
        assertTrue(observed.get(1).signing().isPresent());
        assertTrue(observed.get(1).central().isEmpty());
        assertTrue(observed.get(2).signing().isPresent());
        assertTrue(observed.get(2).central().isPresent());
        assertTrue(observed.get(2).central().orElseThrow().name().isEmpty());
        assertTrue(observed.get(2).central().orElseThrow().url().isEmpty());
        assertEquals("Zolt Release", complete.central().orElseThrow().name().orElseThrow());
        assertEquals(
                "https://central.example.test/api/",
                complete.central().orElseThrow().url().orElseThrow().value());
        assertThrows(
                UnsupportedOperationException.class,
                () -> observed.getFirst().repositories().clear());
    }

    @Test
    void preservesExplicitEmptyRepositoryPresenceAsOneEmptySnapshot() {
        for (String source : List.of(
                "[publish.repositories]\n",
                "publish = { repositories = {} }\n")) {
            ArrayList<AuthoredPublishing> observed = new ArrayList<>();

            AuthoredPublishing publishing =
                    decodePublishing(source, observed::add).orElseThrow();

            assertEquals(List.of(AuthoredPublishing.empty()), observed, source);
            assertEquals(AuthoredPublishing.empty(), publishing, source);
        }
    }

    @Test
    void anchorsRejectingObserversAtEachCanonicalBoundary() {
        assertObservedFailure("""
                [publish]
                snapshot = "remote"
                release = "remote"
                [publish.repositories.remote]
                url = "https://repo.example.test/maven"
                """, "`publish.release`");
        assertObservedFailure("""
                publish.snapshot = "remote"
                [publish.repositories.remote]
                url = "https://repo.example.test/maven"
                """, "`publish.snapshot`");
        assertObservedFailure("[publish.repositories]\n", "[publish.repositories]");
        assertObservedFailure("""
                [publish.repositories.remote]
                url = "https://repo.example.test/maven"
                """, "[publish.repositories]");
        assertObservedFailure("publish.signing.method = \"gpg\"\n", "`publish.signing.method`");
        assertObservedFailure("""
                [publish.central]
                tokenEnv = "CENTRAL_TOKEN"
                mode = "manual"
                """, "`publish.central.tokenEnv`");
    }

    @Test
    void completesBaseValidationBeforeObservingAndPreservesSigningLeafPrecedence() {
        AtomicInteger observations = new AtomicInteger();
        ZoltConfigException repository = assertThrows(
                ZoltConfigException.class,
                () -> decodePublishing("""
                        publish.release = "remote"
                        [publish.repositories.remote]
                        url = "relative"
                        """, ignored -> observations.incrementAndGet()));
        assertSemanticFailure(repository, "`publish.repositories.remote.url`", "Invalid repository URL");
        assertEquals(0, observations.get());

        ZoltConfigException route = assertThrows(
                ZoltConfigException.class,
                () -> decodePublishing("""
                        publish.release = "missing"
                        [publish.repositories]
                        """, ignored -> observations.incrementAndGet()));
        assertSemanticFailure(route, "`publish.release`", "undefined repository `missing`");
        assertEquals(0, observations.get());

        ZoltConfigException signing = assertThrows(
                ZoltConfigException.class,
                () -> decodePublishing("""
                        [publish.repositories]
                        [publish.signing]
                        method = "gpg"
                        keyId = " "
                        """, ignored -> observations.incrementAndGet()));
        assertSemanticFailure(signing, "`publish.signing.keyId`", "must not be blank");
        assertEquals(1, observations.get());

        observations.set(0);
        ZoltConfigException mode = assertThrows(
                ZoltConfigException.class,
                () -> decodePublishing("""
                        [publish.repositories]
                        [publish.signing]
                        method = "gpg"
                        [publish.central]
                        tokenEnv = "CENTRAL_TOKEN"
                        """, ignored -> observations.incrementAndGet()));
        assertTrue(mode.getMessage().contains("publish.central.mode"), mode.getMessage());
        assertNull(mode.getCause());
        assertEquals(2, observations.get());
    }

    @Test
    void observesCentralAggregateAfterCollisionValidationAndContinuesLaterLeaves() {
        ArrayList<AuthoredPublishing> observed = new ArrayList<>();
        ZoltConfigException collision = assertThrows(
                ZoltConfigException.class,
                () -> decodePublishing("""
                        [publish.signing]
                        method = "gpg"
                        passphraseEnv = "TOKEN"
                        [publish.central]
                        tokenEnv = "token"
                        mode = "manual"
                        """, observed::add));
        assertSemanticFailure(collision, "`publish.central.tokenEnv`", "differ only by ASCII case");
        assertEquals(1, observed.size());
        assertTrue(observed.getFirst().signing().isPresent());
        assertTrue(observed.getFirst().central().isEmpty());

        observed.clear();
        ZoltConfigException later = assertThrows(
                ZoltConfigException.class,
                () -> decodePublishing("""
                        [publish.repositories]
                        [publish.signing]
                        method = "gpg"
                        [publish.central]
                        tokenEnv = "CENTRAL_TOKEN"
                        mode = "manual"
                        name = " "
                        """, observed::add));
        assertSemanticFailure(later, "`publish.central.name`", "must not be blank");
        assertEquals(3, observed.size());
        assertTrue(observed.get(2).central().orElseThrow().name().isEmpty());
    }

    @Test
    void leavesOmissionAndShapeFailuresUnobservedAndRequiresNonNullInputs() {
        AtomicInteger observations = new AtomicInteger();
        assertTrue(decodePublishing(
                "", ignored -> observations.incrementAndGet()).isEmpty());
        assertEquals(0, observations.get());

        ZoltConfigException shape = assertThrows(
                ZoltConfigException.class,
                () -> decodePublishing(
                        "publish.unknown = true\n",
                        ignored -> observations.incrementAndGet()));
        assertTrue(shape.getMessage().contains("Unknown manifest field"), shape.getMessage());
        assertNull(shape.getCause());
        assertEquals(0, observations.get());

        assertEquals(
                "Manifest decode index is required.",
                assertThrows(
                                NullPointerException.class,
                                () -> decodePublishingWithNullIndex())
                        .getMessage());
        assertEquals(
                "Authored publishing presence observer is required.",
                assertThrows(
                                NullPointerException.class,
                                () -> decodePublishing("", null))
                        .getMessage());
    }

    private static void assertObservedFailure(String source, String path) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodePublishing(source, ignored -> {
                    throw new IllegalArgumentException("Observed authored publishing.");
                }));
        assertSemanticFailure(failure, path, "Observed authored publishing.");
    }

    private static void assertSemanticFailure(
            ZoltConfigException failure,
            String path,
            String detail) {
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }
}
