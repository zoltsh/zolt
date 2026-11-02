package sh.zolt.tree;

import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Matches conflict evidence to the exact main, exec, or compiler package occurrence it describes. */
final class DependencyConflictIndex {
    private final List<LockConflict> conflicts;
    private final String member;

    DependencyConflictIndex(ZoltLockfile lockfile, String member) {
        this.conflicts = lockfile.conflicts();
        this.member = member;
    }

    Optional<LockConflict> first(LockPackage target) {
        return matching(target).stream().findFirst();
    }

    List<LockConflict> matching(LockPackage target) {
        LockArtifactVariant variant = LockArtifactVariant.of(target);
        return conflicts.stream()
                .filter(conflict -> conflict.packageId().equals(target.packageId()))
                .filter(conflict -> conflict.selectedVersion().equals(target.version()))
                .filter(conflict -> conflict.variant()
                        .orElse(LockArtifactVariant.defaultVariant())
                        .equals(variant))
                .filter(conflict -> conflict.members().isEmpty() || conflict.members().contains(member))
                .filter(conflict -> sameResolution(conflict, target))
                .sorted(Comparator.comparing(conflict -> conflict.toolGroup().orElse("")))
                .toList();
    }

    private static boolean sameResolution(LockConflict conflict, LockPackage target) {
        return switch (target.scope()) {
            case TOOL_GROOVY, TOOL_KOTLIN -> conflict.toolGroup()
                    .filter(group -> LockConflict.isCompilerToolGroup(group, target.scope()))
                    .isPresent();
            case TOOL_EXEC -> conflict.toolGroup()
                    .filter(target.toolGroups()::contains)
                    .isPresent();
            default -> conflict.toolGroup().isEmpty();
        };
    }
}
