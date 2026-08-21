package sh.zolt.lockfile.toml;

import java.util.EnumSet;
import java.util.Optional;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;

/** Checks persisted v7 direct-package evidence without constraining ephemeral lock projections. */
final class LockfileDependencyRootCompleteness {
    private static final EnumSet<DependencyScope> DECLARATION_SCOPES = EnumSet.of(
            DependencyScope.COMPILE,
            DependencyScope.RUNTIME,
            DependencyScope.PROVIDED,
            DependencyScope.DEV,
            DependencyScope.TEST,
            DependencyScope.PROCESSOR,
            DependencyScope.TEST_PROCESSOR);

    private LockfileDependencyRootCompleteness() {
    }

    static Optional<String> violation(ZoltLockfile lockfile) {
        return lockfile.packages().stream()
                .filter(LockPackage::direct)
                .filter(lockPackage -> DECLARATION_SCOPES.contains(lockPackage.scope()))
                .filter(lockPackage -> lockfile.dependencyRoots().stream()
                        .filter(root -> !root.publishOnly())
                        .noneMatch(root -> selects(root, lockPackage)))
                .findFirst()
                .map(lockPackage -> "Direct package `" + selectedPackage(lockPackage)
                        + "` has no exact dependencyRoot in zolt.lock.");
    }

    private static boolean selects(LockDependencyRoot root, LockPackage lockPackage) {
        return root.packageId().equals(lockPackage.packageId())
                && root.version().equals(lockPackage.version())
                && root.variant().equals(LockArtifactVariant.of(lockPackage))
                && root.resolvedScope().orElseThrow() == lockPackage.scope();
    }

    private static String selectedPackage(LockPackage lockPackage) {
        return lockPackage.packageId() + ":" + lockPackage.version() + ":"
                + LockArtifactVariant.of(lockPackage).key() + ":" + lockPackage.scope().lockfileName();
    }
}
