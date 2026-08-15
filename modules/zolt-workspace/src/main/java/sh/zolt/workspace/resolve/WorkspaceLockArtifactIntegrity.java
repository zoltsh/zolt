package sh.zolt.workspace.resolve;

import sh.zolt.build.lockfile.ArtifactIntegrityVerifier;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import java.nio.file.Path;

/** Checksum-aware cache gate shared by workspace build and resolve freshness paths. */
final class WorkspaceLockArtifactIntegrity {
    private WorkspaceLockArtifactIntegrity() {
    }

    static boolean valid(
            ZoltLockfile lockfile,
            Path cacheRoot,
            VerifiedArtifactIndex artifactIndex) {
        try {
            verify(lockfile, cacheRoot, artifactIndex);
            return true;
        } catch (LockfileReadException exception) {
            return false;
        }
    }

    static void verify(
            ZoltLockfile lockfile,
            Path cacheRoot,
            VerifiedArtifactIndex artifactIndex) {
        try {
            new ArtifactIntegrityVerifier(artifactIndex).verify(lockfile, cacheRoot);
        } catch (IllegalArgumentException exception) {
            throw LockfileReadException.actionable(
                    "zolt.lock references an unsafe artifact cache path.",
                    "Correct the lockfile path or regenerate zolt.lock with `zolt resolve --workspace`.",
                    exception);
        }
    }
}
