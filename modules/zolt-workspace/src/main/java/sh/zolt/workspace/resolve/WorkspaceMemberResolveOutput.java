package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.ZoltLockfile;
import java.util.Set;

record WorkspaceMemberResolveOutput(
        String member,
        ZoltLockfile lockfile,
        Set<WorkspaceExportedPackage> exportedPackages,
        Set<WorkspaceOptionalPackage> optionalPackages) {
    WorkspaceMemberResolveOutput(
            String member,
            ZoltLockfile lockfile,
            Set<WorkspaceExportedPackage> exportedPackages) {
        this(member, lockfile, exportedPackages, Set.of());
    }

    WorkspaceMemberResolveOutput {
        exportedPackages = Set.copyOf(exportedPackages);
        optionalPackages = Set.copyOf(optionalPackages);
    }
}
