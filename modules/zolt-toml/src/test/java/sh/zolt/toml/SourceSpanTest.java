package sh.zolt.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SourceSpanTest {
    @Test
    void usesHalfOpenUtf16Offsets() {
        String source = "a🚀z";
        SourceSpan rocket = new SourceSpan(1, 3);
        assertEquals(2, rocket.length());
        assertEquals("🚀", rocket.text(source));
        assertEquals("z", new SourceSpan(3, 4).text(source));
    }

    @Test
    void validatesBoundsAndEmptySpans() {
        assertTrue(SourceSpan.emptyAt(2).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new SourceSpan(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SourceSpan(2, 1));
        assertThrows(IllegalArgumentException.class, () -> new SourceSpan(0, 2).text("x"));
    }
}
