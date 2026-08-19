package sh.zolt.workspace.unicode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class Unicode17PortabilityTest {
    @Test
    void normalizesCanonicalDecompositionAndCombiningOrder() {
        assertEquals("\u00E9", Unicode17Portability.normalizeNfc("e\u0301"));
        assertEquals("\u1E0C\u0307", Unicode17Portability.normalizeNfc("D\u0307\u0323"));
        assertEquals("\u0915\u093C", Unicode17Portability.normalizeNfc("\u0958"));
    }

    @Test
    void composesHangulAlgorithmically() {
        assertEquals("\uAC01", Unicode17Portability.normalizeNfc("\u1100\u1161\u11A8"));
        assertEquals("\u1100\uAC01", Unicode17Portability.normalizeNfc("\u1100\uAC00\u11A8"));
    }

    @Test
    void appliesOnlyFullDefaultCaseFoldMappings() {
        assertEquals("strasse", Unicode17Portability.key("Stra\u00DFe"));
        assertEquals("ss", Unicode17Portability.key("\u1E9E"));
        assertEquals("i\u0307", Unicode17Portability.key("\u0130"));
        assertEquals("i", Unicode17Portability.key("I"));
    }

    @Test
    void normalizesAgainAfterMultiCodePointFolding() {
        assertEquals("\u01F0", Unicode17Portability.key("\u01F0"));
    }

    @Test
    void usesUnicode17MappingsForBmpAndSupplementaryCharacters() {
        assertEquals("\uA7CF", Unicode17Portability.key("\uA7CE"));
        assertEquals("\uD81B\uDEBB", Unicode17Portability.key("\uD81B\uDEA0"));
    }

    @Test
    void rejectsUnpairedUtf16Surrogates() {
        assertThrows(IllegalArgumentException.class, () -> Unicode17Portability.key("a\uD800b"));
        assertThrows(IllegalArgumentException.class, () -> Unicode17Portability.normalizeNfc("\uDC00"));
    }
}
