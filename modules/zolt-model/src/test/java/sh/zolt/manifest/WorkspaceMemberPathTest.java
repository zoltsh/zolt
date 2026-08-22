package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** Design §6.3: only `*` and `?` are pattern syntax; brackets and braces are literal names. */
    @Test
    void acceptsBracketAndBraceDirectoryNamesAsLiteralSegments() {
        assertEquals("modules/[ab]", new WorkspaceMemberPath("modules/[ab]").value());
        assertEquals("apps/notes[draft]", new WorkspaceMemberPath("apps/notes[draft]").value());
        assertEquals("{apps,modules}/api", new WorkspaceMemberPath("{apps,modules}/api").value());
    }

    @Test
    void reportsWhyANonportableDirectoryNameCannotCarryMemberIdentity() {
        assertEquals(java.util.Optional.empty(), WorkspaceMemberPath.problem("apps/notes[draft]"));
        assertTrue(WorkspaceMemberPath.problem("apps/we?rd").orElseThrow()
                .contains("without pattern syntax"));
    }

    @Test
    void rejectsPatternsTraversalAndNonportableSeparators() {
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceMemberPath("modules/*"));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceMemberPath("modules/?ore"));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceMemberPath("modules/../core"));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceMemberPath("modules/./core"));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceMemberPath("modules//core"));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceMemberPath("C:\\projects\\core"));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceMemberPath("/modules/core"));
    }
}
