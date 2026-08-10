package sh.zolt.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class UpdateTargetIdTest {
    private static final String DEPENDENCY_ID =
            "zt1_vcc-lFhiR4a_S4Vab01gw0_gcPDgShIiT8IdjXa5MhM";

    @Test
    void matchesPublicFixedVectors() {
        assertEquals(DEPENDENCY_ID, id(
                "apps/api/zolt.toml",
                OutdatedSurface.DEPENDENCY,
                "[dependencies]",
                "com.google.guava:guava").value());
        assertEquals(
                "zt1_Ar_b-SXZMAoz9q5_BrDWoPB7EyXy8EIu5r3RDmB6QF8",
                id("zolt.toml", OutdatedSurface.VERSION_ALIAS, "[versions]", "junit").value());
        assertEquals(
                "zt1_7JDO7hkQrBl5dUC14pm3rxY9MvxgOtULf2HZW3iM3j0",
                id("zolt.toml", OutdatedSurface.DEPENDENCY, "[dependencies]", "com.example:lib").value());
    }

    @Test
    void parsesOnlyCanonicalVersionOneIds() {
        UpdateTargetId parsed = UpdateTargetId.parse(DEPENDENCY_ID);

        assertEquals(DEPENDENCY_ID, parsed.value());
        assertEquals(parsed, UpdateTargetId.parse(parsed.toString()));
        for (String invalid : List.of(
                "",
                "zt2_" + "a".repeat(43),
                "zt1_" + "a".repeat(42),
                "zt1_" + "a".repeat(43) + "=",
                "zt1_" + "+" + "a".repeat(42),
                "zt1_" + "a".repeat(42) + "h")) {
            assertThrows(IllegalArgumentException.class, () -> UpdateTargetId.parse(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> UpdateTargetId.parse(null));
    }

    @Test
    void everyCanonicalIdentityFieldAffectsTheDigest() {
        UpdateTargetId baseline = id(
                "apps/api/zolt.toml",
                OutdatedSurface.DEPENDENCY,
                "[dependencies]",
                "com.example:lib");

        assertNotEquals(baseline, id(
                "apps/other/zolt.toml",
                OutdatedSurface.DEPENDENCY,
                "[dependencies]",
                "com.example:lib"));
        assertNotEquals(baseline, id(
                "apps/api/zolt.toml",
                OutdatedSurface.PLATFORM,
                "[dependencies]",
                "com.example:lib"));
        assertNotEquals(baseline, id(
                "apps/api/zolt.toml",
                OutdatedSurface.DEPENDENCY,
                "[test.dependencies]",
                "com.example:lib"));
        assertNotEquals(baseline, id(
                "apps/api/zolt.toml",
                OutdatedSurface.DEPENDENCY,
                "[dependencies]",
                "com.example:other"));
    }

    @Test
    void descriptiveAndDestinationStateDoNotAffectIdentity() {
        UpdateTargetId targetId = id(
                "apps/api/zolt.toml",
                OutdatedSurface.VERSION_ALIAS,
                "[versions]",
                "shared");
        UpdateTarget before = target(targetId, "1.0.0", true, Optional.empty(), List.of("[dependencies].com.a:one"));
        UpdateTarget after = target(
                targetId,
                "2.0.0",
                false,
                Optional.of("temporarily blocked"),
                List.of("[dependencies].com.b:two"));

        assertEquals(before.targetId(), after.targetId());
    }

    @Test
    void rejectsNoncanonicalPathsTextAndUnicode() {
        for (String path : List.of(
                "/zolt.toml",
                "apps\\api\\zolt.toml",
                "./zolt.toml",
                "apps/../zolt.toml",
                "apps//zolt.toml")) {
            assertThrows(IllegalArgumentException.class, () -> id(
                    path,
                    OutdatedSurface.DEPENDENCY,
                    "[dependencies]",
                    "com.example:lib"));
        }
        assertThrows(IllegalArgumentException.class, () -> id(
                "zolt.toml",
                OutdatedSurface.DEPENDENCY,
                "[dependencies]",
                "com.example:lib\n"));
        assertThrows(IllegalArgumentException.class, () -> id(
                "zolt.toml",
                OutdatedSurface.VERSION_ALIAS,
                "[versions]",
                "cafe\u0301"));
    }

    private static UpdateTarget target(
            UpdateTargetId targetId,
            String currentVersion,
            boolean updateable,
            Optional<String> blocker,
            List<String> governs) {
        return new UpdateTarget(
                targetId,
                "apps/api/zolt.toml",
                "zolt.lock",
                OutdatedSurface.VERSION_ALIAS,
                "shared",
                "[versions]",
                currentVersion,
                updateable,
                blocker,
                governs);
    }

    private static UpdateTargetId id(
            String manifestPath,
            OutdatedSurface surface,
            String section,
            String identifier) {
        return UpdateTargetId.create(manifestPath, surface, section, identifier);
    }
}
