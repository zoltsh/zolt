package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.toml.AtomicLockfileWriter;
import sh.zolt.lockfile.toml.LockfileSidecars;
import sh.zolt.workspace.service.Workspace;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Records the resolution-input fingerprint on a lock a full locked verification just accepted.
 *
 * <p>Verification proves the lock is exactly what these inputs derive, so recording the fingerprint
 * is sound and it is what stops the slow path from sticking: an input edit the lock forgives — a
 * comment, or a lock written before the fingerprint existed — would otherwise make every later
 * command re-run the full resolve for the life of the lock, silently.
 *
 * <p>Recording is an optimisation, never a requirement, so a lock the process may not write (a
 * read-only checkout, a sandboxed CI stage) simply keeps taking the slow path instead of failing a
 * command that has already succeeded.
 */
final class WorkspaceLockFingerprintRecorder {
    private WorkspaceLockFingerprintRecorder() {
    }

    static void record(Workspace workspace, Path lockfilePath) {
        try {
            AtomicLockfileWriter.update(lockfilePath, existing -> recorded(workspace, existing));
        } catch (NothingToRecord nothingToRecord) {
            // Thrown before the writer commits, so the lock on disk is left exactly as it was.
        } catch (IOException | RuntimeException exception) {
            // The command already succeeded; a lock that cannot be updated stays on the slow path.
        }
    }

    /**
     * The fingerprint is computed inside the writer's read-modify-write transaction, over the exact
     * bytes being replaced, so a concurrent resolve cannot leave a value certifying other content.
     */
    private static String recorded(Workspace workspace, String existing) {
        if (existing == null || existing.isBlank()) {
            throw new NothingToRecord();
        }
        String updated = WorkspaceResolutionInputFingerprint.fingerprint(workspace, existing)
                .map(value -> LockfileSidecars
                        .withWorkspaceResolutionInputFingerprint(existing, value))
                .orElse(existing);
        if (updated.equals(existing)) {
            throw new NothingToRecord();
        }
        return updated;
    }

    /** Abandons the transaction so an unchanged or vanished lock is never rewritten. */
    private static final class NothingToRecord extends RuntimeException {
        private NothingToRecord() {
            super(null, null, false, false);
        }
    }
}
