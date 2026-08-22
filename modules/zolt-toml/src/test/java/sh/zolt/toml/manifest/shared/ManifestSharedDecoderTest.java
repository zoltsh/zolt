package sh.zolt.toml.manifest.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestSharedTestSupport.decodeShared;

import org.junit.jupiter.api.Test;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredVersionAliases;
import sh.zolt.toml.manifest.ManifestSharedTestSupport.Decoded;

final class ManifestSharedDecoderTest {
    @Test
    void preservesOmittedSharedDomains() {
        Decoded decoded = decode("");

        assertTrue(decoded.versions().isEmpty());
        assertTrue(decoded.repositories().isEmpty());
        assertTrue(decoded.credentials().isEmpty());
        assertTrue(decoded.platforms().isEmpty());
    }

    @Test
    void preservesExplicitEmptyCollectionDomains() {
        Decoded decoded = decode("""
                [versions]

                [credentials]

                [platforms]
                """);

        assertEquals(AuthoredVersionAliases.empty(), decoded.versions().orElseThrow());
        assertEquals(AuthoredCredentials.empty(), decoded.credentials().orElseThrow());
        assertEquals(AuthoredPlatforms.empty(), decoded.platforms().orElseThrow());
        assertFalse(decoded.repositories().isPresent());
    }

    @Test
    void coordinatesAllFourPresentDomainsWithoutComposingReferences() {
        Decoded decoded = decode("""
                [versions]
                release = "1.0.0"

                [repositories.company]
                url = "https://repo.example.com/maven"
                credentials = "later"

                [credentials.release]
                tokenEnv = "RELEASE_TOKEN"

                [platforms]
                "org.example:platform" = { versionRef = "missing-for-now" }
                """);

        assertEquals("1.0.0", decoded.versions().orElseThrow()
                .entries().values().iterator().next().value());
        assertEquals(1, decoded.repositories().orElseThrow().named().size());
        assertEquals(1, decoded.credentials().orElseThrow().entries().size());
        assertEquals(1, decoded.platforms().orElseThrow().entries().size());
    }

    private static Decoded decode(String source) {
        return decodeShared(source);
    }
}
