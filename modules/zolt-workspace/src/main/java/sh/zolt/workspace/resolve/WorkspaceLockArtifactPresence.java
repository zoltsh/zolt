package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.CacheRelativePath;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPackageCachePath;
import sh.zolt.lockfile.LockPackagePathKind;
import sh.zolt.lockfile.ZoltLockfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Whether every cache artifact a root lock names is already on disk.
 *
 * <p>The locked workspace resolve the freshness gate skips is also the only step that materializes
 * locked artifacts, so skipping it against a cold or evicted cache would defer the failure to a
 * misleading integrity error during the build. The gate therefore proves presence before it skips,
 * and hands the resolve back the work when anything is absent.
 *
 * <p>This is existence only — one {@code stat} per distinct path, no hashing and no reads — because
 * content is exactly what the resolve and the build's own integrity verification already check.
 * Workspace members contribute nothing: their packages carry build outputs rather than cache paths.
 */
final class WorkspaceLockArtifactPresence {
    private WorkspaceLockArtifactPresence() {
    }

    /** True when {@code cacheRoot} holds every artifact path {@code lockfile} references. */
    static boolean complete(ZoltLockfile lockfile, Path cacheRoot) {
        Path root = cacheRoot.toAbsolutePath().normalize();
        for (CacheRelativePath relativePath : cacheRelativePaths(lockfile)) {
            if (!Files.isRegularFile(relativePath.resolveWithin(root))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The distinct cache-relative artifact paths, deduplicated because one artifact is commonly
     * shared by many members and would otherwise be stated once per member.
     */
    private static Set<CacheRelativePath> cacheRelativePaths(ZoltLockfile lockfile) {
        Set<CacheRelativePath> paths = new LinkedHashSet<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            LockPackageCachePath.path(lockPackage, LockPackagePathKind.JAR).ifPresent(paths::add);
            LockPackageCachePath.path(lockPackage, LockPackagePathKind.POM).ifPresent(paths::add);
            LockPackageCachePath.path(lockPackage, LockPackagePathKind.SECONDARY).ifPresent(paths::add);
        }
        return paths;
    }
}
