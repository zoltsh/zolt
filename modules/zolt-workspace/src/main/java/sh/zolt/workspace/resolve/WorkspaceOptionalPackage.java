package sh.zolt.workspace.resolve;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;

/** One member-qualified package identity that lies behind an optional dependency boundary. */
record WorkspaceOptionalPackage(
        PackageId packageId,
        LockArtifactVariant variant,
        DependencyScope scope) {
}
