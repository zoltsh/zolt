package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.resolve.ResolveResult;
import java.util.Arrays;
import java.util.Objects;

/**
 * Exact lockfile identity committed by one workspace resolve transaction.
 *
 * <p>{@code resolutionSkipped} records that the lock was already current, so nothing was resolved and
 * nothing was written; the bytes are the ones already on disk. Callers that only need the lock cannot
 * tell the difference, which is the point — it is reported so a command can say "up to date" instead
 * of "wrote".
 */
public record WorkspaceResolveSnapshot(
        ResolveResult result,
        byte[] committedLockfileBytes,
        ZoltLockfile lockfile,
        boolean resolutionSkipped) {
    public WorkspaceResolveSnapshot(
            ResolveResult result,
            byte[] committedLockfileBytes,
            ZoltLockfile lockfile) {
        this(result, committedLockfileBytes, lockfile, false);
    }

    public WorkspaceResolveSnapshot {
        Objects.requireNonNull(result, "result");
        committedLockfileBytes = Objects.requireNonNull(
                committedLockfileBytes,
                "committedLockfileBytes").clone();
        Objects.requireNonNull(lockfile, "lockfile");
    }

    @Override
    public byte[] committedLockfileBytes() {
        return committedLockfileBytes.clone();
    }

    public boolean matchesCommittedLockfile(byte[] candidate) {
        return candidate != null
                && Arrays.equals(committedLockfileBytes, candidate);
    }
}
