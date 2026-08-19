package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ZoltVersionPinTest {
    @Test
    void acceptsExactReleaseAndSnapshotPins() {
        assertEquals("0.1.0", new ZoltVersionPin("0.1.0").value());
        assertEquals("0.1.0-SNAPSHOT", new ZoltVersionPin("0.1.0-SNAPSHOT").toString());
        assertEquals(
                "0.1.0-nightly.20260819.0123456789ab",
                new ZoltVersionPin("0.1.0-nightly.20260819.0123456789ab").value());
    }

    @Test
    void rejectsChannelsSelectorsArbitraryTextControlsAndPaths() {
        for (String value : List.of(
                "", "stable", "preview", "not-a-version", "[0.1,0.2)", "^0.1.0", "0.+", "1.*",
                "latest", "${ZOLT_VERSION}", "0.1.", "../0.1.0", "releases/0.1.0",
                "releases\\0.1.0", " 0.1.0", "0.1.0\0bad", "0.1.0\nbad")) {
            assertThrows(IllegalArgumentException.class, () -> new ZoltVersionPin(value), value);
        }
        assertThrows(NullPointerException.class, () -> new ZoltVersionPin(null));
    }
}
