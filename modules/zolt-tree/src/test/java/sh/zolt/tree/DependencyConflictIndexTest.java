package sh.zolt.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DependencyConflictIndexTest {
    @Test
    void matchesCompilerScopePrefixAndWorkspaceMember() {
        PackageId shared = new PackageId("com.example", "shared");
        String kotlin = LockConflict.compilerToolGroup(DependencyScope.TOOL_KOTLIN, "kotlin-2.2.0");
        LockPackage target = new LockPackage(
                shared,
                "2.0.0",
                "maven-central",
                DependencyScope.TOOL_KOTLIN,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(target),
                List.of(
                        conflict(shared, "main", Optional.empty(), List.of("apps/api")),
                        conflict(
                                shared,
                                "groovy",
                                Optional.of(LockConflict.compilerToolGroup(
                                        DependencyScope.TOOL_GROOVY, "groovy-4.0.23")),
                                List.of("apps/api")),
                        conflict(shared, "other-member", Optional.of(kotlin), List.of("apps/worker")),
                        conflict(shared, "kotlin", Optional.of(kotlin), List.of("apps/api"))));

        assertEquals(
                List.of("kotlin"),
                new DependencyConflictIndex(lockfile, "apps/api").matching(target).stream()
                        .map(conflict -> conflict.requestedVersions().getFirst())
                        .toList());
    }

    private static LockConflict conflict(
            PackageId packageId,
            String marker,
            Optional<String> toolGroup,
            List<String> members) {
        return new LockConflict(
                packageId,
                "2.0.0",
                List.of(marker, "2.0.0"),
                ConflictSelectionReason.NEWEST_VERSION,
                toolGroup,
                Optional.empty(),
                members);
    }
}
