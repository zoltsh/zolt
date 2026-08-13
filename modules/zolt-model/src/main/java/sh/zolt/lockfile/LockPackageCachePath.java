package sh.zolt.lockfile;

import java.util.Optional;

/** Typed access to the cache-backed artifact path fields of a lock package. */
public final class LockPackageCachePath {
    private LockPackageCachePath() {
    }

    public static Optional<CacheRelativePath> path(
            LockPackage lockPackage,
            LockPackagePathKind kind) {
        return (switch (kind) {
            case JAR -> lockPackage.jar();
            case POM -> lockPackage.pom();
            case SECONDARY -> lockPackage.artifact();
        }).map(CacheRelativePath::new);
    }
}
