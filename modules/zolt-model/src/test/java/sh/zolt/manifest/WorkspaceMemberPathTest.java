package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class WorkspaceMemberPathTest {
    @Test
    void acceptsRootAndNormalizesExactMemberPathsToUnicodeNfc() {
        assertEquals(".", new WorkspaceMemberPath(".").value());
        assertEquals(
                "modules/caf\u00E9",
                new WorkspaceMemberPath("modules/cafe\u0301").value());
        assertEquals(".hidden/member", new WorkspaceMemberPath(".hidden/member").value());
    }

    @Test
    void rejectsPatternsTraversalAndNonportableSeparators() {
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceMemberPath("modules/*"));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceMemberPath("modules/?ore"));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceMemberPath("modules/[ab]"));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceMemberPath("modules/../core"));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceMemberPath("modules/./core"));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceMemberPath("modules//core"));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceMemberPath("C:\\projects\\core"));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceMemberPath("/modules/core"));
    }
}
