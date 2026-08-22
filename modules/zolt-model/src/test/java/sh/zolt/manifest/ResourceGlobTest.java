package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ResourceGlobTest {
    @Test
    void acceptsOnlyThePinnedRootRelativeGlobGrammar() {
        assertEquals("**/*.properties", new ResourceGlob("**/*.properties").value());
        assertEquals("config/app?.yaml", new ResourceGlob("config/app?.yaml").value());
        assertEquals("caf\u00e9/*.txt", new ResourceGlob("cafe\u0301/*.txt").value());

        for (String invalid : List.of(
                "", "/absolute", "C:/absolute", "a\\b", "../x", "a/./b",
                "a//b", "foo**/x", "a/[bc]", "a/{b,c}", "a/\n")) {
            assertThrows(IllegalArgumentException.class, () -> new ResourceGlob(invalid), invalid);
        }
    }
}
