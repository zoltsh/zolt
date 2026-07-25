package sh.zolt.conflict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.dependency.PackageId;
import sh.zolt.dependency.ConflictSelectionReason;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DependencyConflictFormatterTest {
    private final DependencyConflictFormatter formatter = new DependencyConflictFormatter();

    @Test
    void formatsConflictsDeterministically() {
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(),
                List.of(
                        new LockConflict(
                                new PackageId("org.slf4j", "slf4j-api"),
                                "2.0.16",
                                List.of("2.0.16", "1.7.36"),
                                ConflictSelectionReason.DIRECT_DEPENDENCY),
                        new LockConflict(
                                new PackageId("com.google.guava", "guava"),
                                "33.4.0-jre",
                                List.of("32.1.3-jre", "33.4.0-jre"),
                                ConflictSelectionReason.NEWEST_VERSION)));

        String output = formatter.format(lockfile);

        assertEquals("""
                Dependency conflicts:
                - com.google.guava:guava
                  selected: 33.4.0-jre
                  requested: 32.1.3-jre, 33.4.0-jre
                  reason: newest version wins
                - org.slf4j:slf4j-api
                  selected: 2.0.16
                  requested: 1.7.36, 2.0.16
                  reason: direct dependency wins
                """, output);
    }

    @Test
    void rendersToolAttributionAndOrdersMainBeforeToolClosures() {
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(),
                List.of(
                        new LockConflict(
                                new PackageId("com.example", "shared"),
                                "2.0.0",
                                List.of("2.0.0", "1.0.0"),
                                ConflictSelectionReason.NEWEST_VERSION,
                                Optional.of("codegen")),
                        new LockConflict(
                                new PackageId("com.example", "shared"),
                                "1.5.0",
                                List.of("1.5.0", "1.0.0"),
                                ConflictSelectionReason.DIRECT_DEPENDENCY)));

        String output = formatter.format(lockfile);

        // Same GA mediates in the main graph and in the codegen closure: main (no tool) first, then the tool.
        assertEquals("""
                Dependency conflicts:
                - com.example:shared
                  selected: 1.5.0
                  requested: 1.0.0, 1.5.0
                  reason: direct dependency wins
                - com.example:shared
                  tool: codegen
                  selected: 2.0.0
                  requested: 1.0.0, 2.0.0
                  reason: newest version wins
                """, output);
    }

    @Test
    void reportsNoConflictsSuccessfully() {
        String output = formatter.format(new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, List.of(), List.of()));

        assertEquals("No dependency conflicts found.\n", output);
    }

    @Test
    void rendersAndSortsIndependentVariantConflicts() {
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(),
                List.of(
                        new LockConflict(
                                new PackageId("com.example", "native"),
                                "2.0.0",
                                List.of("1.0.0", "2.0.0"),
                                ConflictSelectionReason.NEWEST_VERSION,
                                Optional.empty(),
                                Optional.of(new LockArtifactVariant("jar", Optional.of("tests")))),
                        new LockConflict(
                                new PackageId("com.example", "native"),
                                "3.0.0",
                                List.of("2.0.0", "3.0.0"),
                                ConflictSelectionReason.NEWEST_VERSION,
                                Optional.empty(),
                                Optional.of(new LockArtifactVariant("zip", Optional.empty())))));

        String output = formatter.format(lockfile);

        assertTrue(output.indexOf("variant: jar|tests") < output.indexOf("variant: zip"));
        assertTrue(output.contains("variant: jar|tests"));
        assertTrue(output.contains("variant: zip"));
    }

    @Test
    void rendersWorkspaceMemberAttributionDeterministically() {
        LockConflict conflict = new LockConflict(
                new PackageId("com.example", "shared"),
                "2.0.0",
                List.of("1.0.0", "2.0.0"),
                ConflictSelectionReason.NEWEST_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of("modules/zeta", "apps/alpha"));

        String output = formatter.format(new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(),
                List.of(conflict)));

        assertTrue(output.contains("  members: apps/alpha, modules/zeta\n"));
    }
}
