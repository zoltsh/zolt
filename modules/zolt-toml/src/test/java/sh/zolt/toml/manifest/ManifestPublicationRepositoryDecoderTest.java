package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.authored.AuthoredPublicationRepository;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestPaths;

final class ManifestPublicationRepositoryDecoderTest {
    private final ManifestPublicationRepositoryDecoder decoder = new ManifestPublicationRepositoryDecoder();
    @Test
    void decodesAuthenticatedAndUnauthenticatedRepositoriesWithoutDefaults() {
        ManifestPublicationRepositoryDecoder.Decoded decoded = decode("""
                credentials = "publishing"
                url = "https://repo.example.com/releases///?channel=stable"
                """);
        assertEquals(new LocalId("remote"), decoded.id());
        assertEquals("https://repo.example.com/releases///?channel=stable", decoded.repository().url().value());
        assertEquals("https://repo.example.com/releases?channel=stable", decoded.repository().url().normalizedIdentity());
        assertEquals(new LocalId("publishing"), decoded.repository().credentials().orElseThrow());
        AuthoredPublicationRepository unauthenticated = decode("url = \"http://127.0.0.1:8080/releases\"\n").repository();
        assertTrue(unauthenticated.credentials().isEmpty());
    }
    @Test
    void anchorsUrlThenCredentialsInCanonicalOrder() {
        assertSemanticFailure("url = \"http://repo.example.com/releases\"\n", ".url", "use HTTPS");
        assertSemanticFailure(
                "url = \"https://repo.example.com\"\ncredentials = \"Bad_Id\"\n",
                ".credentials",
                "Invalid local ID");
        assertSemanticFailure(
                "credentials = \"Bad_Id\"\nurl = \"http://repo.example.com\"\n",
                ".url",
                "use HTTPS");
    }
    @Test
    void reportsMissingUrlAtTheConcreteRowPath() {
        ZoltConfigException failure = assertFailure("credentials = \"publishing\"\n");
        assertTrue(failure.getMessage().contains("publish.repositories.remote.url"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Missing required manifest field"), failure.getMessage());
        assertNull(failure.getCause());
    }
    @Test
    void leavesRowStructureToShapeValidation() {
        assertShapeFailure("[publish.repositories.Bad_Id]\nurl = \"https://repo.example.com\"\n", "Invalid dynamic key");
        assertShapeFailure("[publish.repositories.remote]\n", "must not be empty");
        assertShapeFailure("[publish.repositories.remote]\nurl = 42\n", "expected string but found integer");
    }
    @Test
    void enforcesRetainedIdentityAndNonNullContracts() {
        ManifestDecodeIndex index = index("remote", "url = \"https://repo.example.com\"\n");
        ManifestDecodeIndex.SectionEntry entry = entry(index);
        ManifestDecodeIndex.SectionEntry other = entry(index("other", "url = \"https://other.example.com\"\n"));
        AuthoredPublicationRepository repository = AuthoredPublicationRepository.unauthenticated(new RepositoryUrl("https://repo.example.com"));
        assertThrows(NullPointerException.class, () -> decoder.decode(null, entry));
        assertThrows(NullPointerException.class, () -> decoder.decode(index, null));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(index, other));
        assertThrows(NullPointerException.class, () -> new ManifestPublicationRepositoryDecoder.Decoded(null, repository));
        assertThrows(NullPointerException.class, () -> new ManifestPublicationRepositoryDecoder.Decoded(new LocalId("remote"), null));
    }
    private ManifestPublicationRepositoryDecoder.Decoded decode(String fields) {
        ManifestDecodeIndex index = index("remote", fields);
        return decoder.decode(index, entry(index));
    }
    private static ManifestDecodeIndex index(String id, String fields) {
        return ManifestSemanticTestSupport.index("[publish.repositories." + id + "]\n" + fields);
    }
    private static ManifestDecodeIndex.SectionEntry entry(ManifestDecodeIndex index) {
        return index.sectionEntries(FinalManifestPaths.PUBLISH_REPOSITORY).getFirst();
    }
    private ZoltConfigException assertSemanticFailure(String fields, String suffix, String detail) {
        ZoltConfigException failure = assertFailure(fields);
        assertTrue(failure.getMessage().contains("`publish.repositories.remote" + suffix + "`"), failure.getMessage());
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        return failure;
    }
    private ZoltConfigException assertFailure(String fields) {
        return assertThrows(ZoltConfigException.class, () -> decode(fields));
    }
    private static void assertShapeFailure(String source, String detail) {
        ZoltConfigException failure = assertThrows(ZoltConfigException.class, () -> ManifestSemanticTestSupport.index(source));
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertNull(failure.getCause());
    }
}
