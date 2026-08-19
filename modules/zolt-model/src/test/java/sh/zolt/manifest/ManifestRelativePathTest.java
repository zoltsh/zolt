package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ManifestRelativePathTest {
    @Test
    void normalizesToPinnedUnicodeNfcAndPreservesDirectoryEntryCase() {
        ManifestRelativePath path = new ManifestRelativePath("Src/cafe\u0301/Main.java");

        assertEquals("Src/caf\u00e9/Main.java", path.value());
        assertEquals(path.value(), path.toString());
    }

    @Test
    void rejectsNonRelativeOrNonCanonicalLexicalShapes() {
        for (String value : List.of(
                "",
                "/src/main",
                "C:/src/main",
                "c:src/main",
                "src\\main",
                "src//main",
                "src/./main",
                "src/../main",
                "src/main/",
                "src/\u0000main",
                "src/\u001fmain")) {
            assertThrows(IllegalArgumentException.class, () -> new ManifestRelativePath(value), value);
        }
        assertThrows(NullPointerException.class, () -> new ManifestRelativePath(null));
        assertThrows(IllegalArgumentException.class, () -> new ManifestRelativePath("src/\ud800/main"));
    }

    @Test
    void comparesByUnicodeCodePointRatherThanUtf16CodeUnit() {
        ManifestRelativePath bmp = new ManifestRelativePath("src/\ue000");
        ManifestRelativePath supplementary = new ManifestRelativePath("src/\ud800\udc00");

        assertTrue(bmp.compareTo(supplementary) < 0);
        assertTrue(supplementary.compareTo(bmp) > 0);
    }
}
