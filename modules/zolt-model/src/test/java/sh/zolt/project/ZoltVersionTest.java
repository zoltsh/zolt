package sh.zolt.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ZoltVersionTest {
    @Test
    void acceptsTheProductReleaseVersionGrammarExactly() {
        for (String value : List.of(
                "0.1.0",
                "0.1.0-SNAPSHOT",
                "0.1.0-rc.1",
                "0.1.0-nightly.20260819.0123456",
                "dev-zap.20260819.0123456789ab")) {
            assertEquals(value, new ZoltVersion(value).value());
        }
    }

    @Test
    void rejectsChannelsSelectorsWhitespaceAndNonversions() {
        for (String value : List.of(
                "", "stable", "preview", "latest", "^0.1.0", "1.*", "1.2", "0.1.0-", " 0.1.0")) {
            assertThrows(IllegalArgumentException.class, () -> new ZoltVersion(value), value);
        }
        assertThrows(NullPointerException.class, () -> new ZoltVersion(null));
    }
}
