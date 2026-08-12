package sh.zolt.cli.command.dependency;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.update.UpdateTargetKey;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** One authoritative exact-update catalog scope under a confirmed mutation root. */
sealed interface CatalogUpdateScope permits ResolvedUpdateScope, ResolvedWorkspaceUpdateScope {
    Path mutationRoot();

    Path projectDirectory();

    String label();

    String manifestPath();

    String lockfilePath();

    Optional<ZoltLockfile> lockfile();

    Map<UpdateTargetKey, String> targetBlockers();

    default Path absoluteManifestPath() {
        return mutationRoot().resolve(manifestPath()).normalize();
    }

    default Path absoluteLockfilePath() {
        return mutationRoot().resolve(lockfilePath()).normalize();
    }
}
