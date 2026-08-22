package sh.zolt.lockfile.toml;

import java.util.EnumSet;
import java.util.Optional;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;

/**
 * Checks persisted v7 direct-package evidence without constraining ephemeral lock projections.
 *
 * <p>Selection is {@link LockDependencyRoot#selects(LockPackage)} itself rather than a local copy of
 * its coordinate comparison: the shared rule also requires the root's member to be one the package is
 * attributed to, so a root naming a member that never declared the dependency no longer passes as
 * evidence for it.
 */
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
                        .noneMatch(root -> root.selects(lockPackage)))
                .findFirst()
                .map(lockPackage -> "Direct package `" + selectedPackage(lockPackage)
                        + "` has no exact dependencyRoot in zolt.lock.");
    }

    private static String selectedPackage(LockPackage lockPackage) {
        return lockPackage.packageId() + ":" + lockPackage.version() + ":"
                + LockArtifactVariant.of(lockPackage).key() + ":" + lockPackage.scope().lockfileName();
    }
}
