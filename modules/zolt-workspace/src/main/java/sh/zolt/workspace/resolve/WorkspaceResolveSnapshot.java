package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.resolve.ResolveResult;
import java.util.Arrays;
import java.util.Objects;

/** Exact lockfile identity committed by one workspace resolve transaction. */
public record WorkspaceResolveSnapshot(
        ResolveResult result,
        byte[] committedLockfileBytes,
        ZoltLockfile lockfile) {
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
