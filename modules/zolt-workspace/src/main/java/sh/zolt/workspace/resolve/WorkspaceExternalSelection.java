package sh.zolt.workspace.resolve;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockPackage;
import java.util.List;

record WorkspaceExternalSelection(
        List<LockPackage> packages,
        List<LockConflict> conflicts,
        List<LockMemberGraph> memberGraphs) {
    WorkspaceExternalSelection(
            List<LockPackage> packages,
            List<LockConflict> conflicts) {
        this(packages, conflicts, List.of());
    }

    WorkspaceExternalSelection {
        packages = List.copyOf(packages);
        conflicts = List.copyOf(conflicts);
        memberGraphs = List.copyOf(memberGraphs);
    }

    record VersionSelection(
            String version,
            ConflictSelectionReason reason) {
    }
}
