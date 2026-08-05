package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.workspace.service.Workspace;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The workspace, its root lock, and how the lock was found to be current, returned together so a
 * command does not rediscover the workspace to plan the work it just gated.
 *
 * <p>{@code discoveryNanos} is what that one discovery actually cost. Planning reuses this snapshot
 * rather than walking the member configs again, so this is the only place the cost can be measured
 * and the counter reporting it would otherwise read zero.
 */
public record WorkspaceLockFreshness(
        Workspace workspace,
        Path lockfilePath,
        Optional<ZoltLockfile> lockfile,
        Outcome outcome,
        long discoveryNanos) {
    public enum Outcome {
        /** The recorded resolution-input fingerprint matched, so no resolution work ran. */
        FINGERPRINT_MATCHED("matched"),
        /** A full locked resolve ran and confirmed the lock is current. */
        VERIFIED("verified"),
        /** There is no generated root lock yet, so there was nothing to verify. */
        NOT_GENERATED("not-generated");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public WorkspaceLockFreshness {
        lockfile = lockfile == null ? Optional.empty() : lockfile;
        discoveryNanos = Math.max(0L, discoveryNanos);
    }

    public boolean resolutionSkipped() {
        return outcome != Outcome.VERIFIED;
    }
}
