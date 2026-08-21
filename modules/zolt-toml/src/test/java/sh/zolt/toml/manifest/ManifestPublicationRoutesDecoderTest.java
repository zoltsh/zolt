package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredPublicationRoutes;
import sh.zolt.toml.ZoltConfigException;

final class ManifestPublicationRoutesDecoderTest {
    private final ManifestPublicationRoutesDecoder decoder =
            new ManifestPublicationRoutesDecoder();

    @Test
    void preservesOmissionWhenRoutesAreAbsent() {
        assertTrue(decode("").isEmpty());
        assertTrue(decode("[publish.repositories]\n").isEmpty());
    }

    @Test
    void decodesBothRoutesInCanonicalOrderWithoutResolvingRepositories() {
        AuthoredPublicationRoutes routes = decode("""
                [publish]
                snapshot = "snapshots"
                release = "releases"
                """).orElseThrow();

        assertEquals(new LocalId("releases"), routes.release().orElseThrow());
        assertEquals(new LocalId("snapshots"), routes.snapshot().orElseThrow());
    }

    @Test
    void retainsEitherRouteAndAllowsTheSameRepositoryForBoth() {
        AuthoredPublicationRoutes release =
                decode("publish.release = \"releases\"\n").orElseThrow();
        assertEquals(Optional.of(new LocalId("releases")), release.release());
        assertTrue(release.snapshot().isEmpty());

        AuthoredPublicationRoutes snapshot =
                decode("publish.snapshot = \"snapshots\"\n").orElseThrow();
        assertTrue(snapshot.release().isEmpty());
        assertEquals(Optional.of(new LocalId("snapshots")), snapshot.snapshot());

        AuthoredPublicationRoutes shared = decode("""
                publish.release = "remote"
                publish.snapshot = "remote"
                """).orElseThrow();
        assertEquals(shared.release(), shared.snapshot());
    }

    @Test
    void anchorsInvalidRouteIdsToTheirExactFields() {
        assertSemanticFailure(
                "publish.release = \"Bad_Id\"\n",
                "publish.release");
        assertSemanticFailure(
                "publish.snapshot = \"Bad_Id\"\n",
                "publish.snapshot");
    }

    @Test
    void followsCanonicalDiagnosticOrderDespiteReverseAssignments() {
        ZoltConfigException failure = assertSemanticFailure("""
                [publish]
                snapshot = "Bad_Snapshot"
                release = "Bad_Release"
                """, "publish.release");

        assertFalse(failure.getMessage().contains("publish.snapshot"), failure.getMessage());
    }

    @Test
    void leavesEmptyTablesWrongKindsAndLegacyRoutesToShapeValidation() {
        assertShapeFailure(
                "[publish]\n",
                "Manifest table `[publish]` must not be empty");
        assertShapeFailure(
                "publish.release = 42\n",
                "expected string but found integer");
        assertShapeFailure(
                "publish.releaseRepository = \"releases\"\n",
                "Unknown manifest field `publish.releaseRepository`");
        assertShapeFailure(
                "[publish.routes]\nrelease = \"releases\"\n",
                "Unknown manifest section `[publish.routes]`");
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decoder.decode(null));
    }

    private Optional<AuthoredPublicationRoutes> decode(String source) {
        return decoder.decode(ManifestSemanticTestSupport.index(source));
    }

    private ZoltConfigException assertSemanticFailure(String source, String path) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        assertTrue(failure.getMessage().contains("`" + path + "`"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Invalid local ID"), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        return failure;
    }

    private void assertShapeFailure(String source, String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertNull(failure.getCause());
    }
}
