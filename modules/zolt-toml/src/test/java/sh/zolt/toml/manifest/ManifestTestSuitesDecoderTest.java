package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredTestSuite;
import sh.zolt.toml.ZoltConfigException;

final class ManifestTestSuitesDecoderTest {
    private final ManifestTestSuitesDecoder decoder = new ManifestTestSuitesDecoder();

    @Test
    void preservesOmissionAndBothExplicitEmptyCollectionForms() {
        assertTrue(decode("").isEmpty());

        for (String source : List.of(
                "[test.suites]\n",
                "test = { suites = {} }\n")) {
            Map<LocalId, AuthoredTestSuite> suites = decode(source).orElseThrow();
            assertTrue(suites.isEmpty());
            assertThrows(UnsupportedOperationException.class, suites::clear);
        }
    }

    @Test
    void namedRowsImplyPresenceAndReturnALocalIdSortedImmutableMap() {
        Map<LocalId, AuthoredTestSuite> suites = decode("""
                [test.suites.zeta]
                tags = ["slow"]

                [test.suites.alpha]
                workers = 1
                """).orElseThrow();

        assertEquals(
                List.of("alpha", "zeta"),
                suites.keySet().stream().map(LocalId::value).toList());
        assertEquals(Optional.of(1), suites.get(id("alpha")).workers());
        assertEquals(List.of("slow"), suites.get(id("zeta")).tags());
        assertThrows(UnsupportedOperationException.class, suites::clear);
        assertThrows(
                UnsupportedOperationException.class,
                () -> suites.put(id("extra"), suites.get(id("alpha"))));
    }

    @Test
    void validatesExplicitAndInlineRowsInSourceOrderBeforeSortingTheResult() {
        for (String source : List.of(
                """
                [test.suites.zeta]
                classes = ["com/example/BadTest"]

                [test.suites.alpha]
                tags = [" "]
                """,
                "test = { suites = { zeta = { classes = [\"com/example/BadTest\"] }, "
                        + "alpha = { tags = [\" \"] } } }\n")) {
            ZoltConfigException failure = assertThrows(
                    ZoltConfigException.class, () -> decode(source));

            assertTrue(
                    failure.getMessage().contains("`test.suites.zeta.classes[0]`"),
                    failure.getMessage());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decoder.decode(null));
    }

    private Optional<Map<LocalId, AuthoredTestSuite>> decode(String source) {
        return decoder.decode(ManifestSemanticTestSupport.index(source));
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }
}
