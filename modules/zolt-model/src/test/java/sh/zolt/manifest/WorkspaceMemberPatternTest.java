package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class WorkspaceMemberPatternTest {
    @Test
    void acceptsExactAndStrictSegmentPatterns() {
        WorkspaceMemberPattern nested = new WorkspaceMemberPattern("services/*/api");

        assertEquals(List.of("services", "*", "api"), nested.segments());
        assertTrue(nested.hasWildcard());
        assertEquals(".", new WorkspaceMemberPattern(".").value());
        assertEquals(List.of("."), new WorkspaceMemberPattern(".").segments());
        assertFalse(new WorkspaceMemberPattern(".").hasWildcard());
        assertTrue(new WorkspaceMemberPattern("*").hasWildcard());
        assertEquals(List.of("*", "*"), new WorkspaceMemberPattern("*/*").segments());
        assertEquals("modules/core", new WorkspaceMemberPattern("modules/core").value());
        assertEquals(".hidden/member", new WorkspaceMemberPattern(".hidden/member").value());
        assertFalse(new WorkspaceMemberPattern("modules/core").hasWildcard());
        assertThrows(UnsupportedOperationException.class, () -> nested.segments().add("other"));
    }

    @Test
    void normalizesLiteralSegmentsWithPinnedUnicodeData() {
        WorkspaceMemberPattern pattern = new WorkspaceMemberPattern("modules/cafe\u0301/*");

        assertEquals("modules/caf\u00e9/*", pattern.value());
        assertEquals(pattern, new WorkspaceMemberPattern("modules/caf\u00e9/*"));
    }

    @Test
    void rejectsUnsupportedPatternAndPathSyntax() {
        List<String> invalid = List.of(
                "",
                "/apps/*",
                "C:/apps/*",
                "c:/apps/*",
                "C:\\apps\\*",
                "apps//api",
                "apps/api/",
                "apps/./api",
                "apps/../api",
                "apps/**",
                "modules/experimental-*",
                "modules/?ore",
                "modules/[ab]*",
                "{apps,modules}/*",
                "apps/\u0000",
                "apps/\tapi");

        invalid.forEach(value -> assertThrows(
                IllegalArgumentException.class,
                () -> new WorkspaceMemberPattern(value),
                value));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkspaceMemberPattern("apps/\ud800"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkspaceMemberPattern("apps/\udc00"));
    }

    @Test
    void ordersByNormalizedUnicodeCodePoints() {
        List<WorkspaceMemberPattern> patterns = new java.util.ArrayList<>(List.of(
                new WorkspaceMemberPattern("z/*"),
                new WorkspaceMemberPattern("a/*"),
                new WorkspaceMemberPattern("a/exact")));

        patterns.sort(null);

        assertEquals(
                List.of("a/*", "a/exact", "z/*"),
                patterns.stream().map(WorkspaceMemberPattern::value).toList());

        WorkspaceMemberPattern supplementary = new WorkspaceMemberPattern("\ud800\udc00");
        WorkspaceMemberPattern privateUse = new WorkspaceMemberPattern("\ue000");
        assertTrue(privateUse.compareTo(supplementary) < 0);
    }

    @Test
    void exposesPinnedPortabilityKeysForCollisionChecks() {
        assertEquals(
                new WorkspaceMemberPattern("modules/STRASSE").portabilityKey(),
                new WorkspaceMemberPattern("modules/Stra\u00dfe").portabilityKey());
    }
}
