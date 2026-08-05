package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
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
        for (String relativePath : cacheRelativePaths(lockfile)) {
            if (!Files.isRegularFile(root.resolve(relativePath))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The distinct cache-relative artifact paths, deduplicated because one artifact is commonly
     * shared by many members and would otherwise be stated once per member.
     */
    private static Set<String> cacheRelativePaths(ZoltLockfile lockfile) {
        Set<String> paths = new LinkedHashSet<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            add(paths, lockPackage.jar());
            add(paths, lockPackage.pom());
            add(paths, lockPackage.artifact());
        }
        return paths;
    }

    private static void add(Set<String> paths, Optional<String> relativePath) {
        relativePath.filter(value -> !value.isBlank()).ifPresent(paths::add);
    }
}
