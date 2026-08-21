package sh.zolt.toml.manifest.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestPublishingTestSupport.decodeRepositories;
import static sh.zolt.toml.manifest.ManifestPublishingTestSupport.decodeRepositoriesWithNullIndex;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredPublicationRepository;
import sh.zolt.toml.ZoltConfigException;

final class ManifestPublicationRepositoriesDecoderTest {
    @Test
    void preservesOmissionAndBothExplicitEmptyCollectionForms() {
        assertTrue(decode("").isEmpty());
        assertTrue(decode("publish.release = \"releases\"\n").isEmpty());

        for (String source : List.of(
                "[publish.repositories]\n",
                "publish = { repositories = {} }\n")) {
            Map<LocalId, AuthoredPublicationRepository> repositories =
                    decode(source).orElseThrow();
            assertTrue(repositories.isEmpty());
            assertThrows(UnsupportedOperationException.class, repositories::clear);
        }
    }

    @Test
    void namedRowsImplyPresenceAndReturnALocalIdSortedImmutableMap() {
        Map<LocalId, AuthoredPublicationRepository> repositories = decode("""
                [publish.repositories.zeta]
                url = "https://zeta.example.test/maven"
                credentials = "company"

                [publish.repositories.alpha]
                url = "https://alpha.example.test/maven"
                """).orElseThrow();

        assertEquals(
                List.of("alpha", "zeta"),
                repositories.keySet().stream().map(LocalId::value).toList());
        assertTrue(repositories.get(id("alpha")).credentials().isEmpty());
        assertEquals(
                Optional.of(id("company")),
                repositories.get(id("zeta")).credentials());
        assertThrows(UnsupportedOperationException.class, repositories::clear);
        assertThrows(
                UnsupportedOperationException.class,
                () -> repositories.put(id("extra"), repositories.get(id("alpha"))));
    }

    @Test
    void validatesExplicitAndInlineRowsInSourceOrderBeforeSortingTheResult() {
        for (String source : List.of(
                """
                [publish.repositories.zeta]
                url = "relative"

                [publish.repositories.alpha]
                url = "also-relative"
                """,
                "publish = { repositories = { zeta = { url = \"relative\" }, "
                        + "alpha = { url = \"also-relative\" } } }\n")) {
            ZoltConfigException failure = assertThrows(
                    ZoltConfigException.class,
                    () -> decode(source));
            assertTrue(
                    failure.getMessage().contains("`publish.repositories.zeta.url`"),
                    failure.getMessage());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decodeRepositoriesWithNullIndex());
    }

    private static Optional<Map<LocalId, AuthoredPublicationRepository>> decode(
            String source) {
        return decodeRepositories(source);
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }
}
