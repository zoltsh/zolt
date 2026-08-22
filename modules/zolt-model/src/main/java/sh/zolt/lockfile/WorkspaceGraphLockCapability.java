package sh.zolt.lockfile;

import sh.zolt.error.ActionableError;
import sh.zolt.error.ActionableException;

/**
 * Capability gate for commands that consume the workspace's member-qualified dependency graph.
 *
 * <p>Historical in-memory models before version 5 do not record optional-boundary evidence.
 * Treating a missing {@code optional} fact as {@code false} changes dependency visibility, so
 * graph consumers must refuse those models rather than guess. Current lock readers reject every
 * pre-v7 wire lock before this capability is consulted.
 */
public final class WorkspaceGraphLockCapability {
    public static final int MINIMUM_VERSION = 5;

    private WorkspaceGraphLockCapability() {
    }

    public static boolean supportsMemberGraphEvidence(ZoltLockfile lockfile) {
        return lockfile.version() >= MINIMUM_VERSION;
    }

    public static void requireMemberGraphEvidence(ZoltLockfile lockfile) {
        if (!supportsMemberGraphEvidence(lockfile)) {
            throw new ActionableException(ActionableError.of(
                    "Workspace zolt.lock version "
                            + lockfile.version()
                            + " lacks the version "
                            + MINIMUM_VERSION
                            + " member-qualified optional-boundary evidence required by workspace graph consumers.",
                    "Run `zolt resolve --workspace` with this Zolt version to regenerate zolt.lock before building, testing, packaging, running, publishing, checking dependency or license policy, generating IDE models, or generating workspace SBOMs."));
        }
        lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.workspace().isEmpty())
                .filter(lockPackage -> lockPackage.members().isEmpty())
                .findFirst()
                .ifPresent(lockPackage -> {
                    throw new ActionableException(ActionableError.of(
                            "Workspace zolt.lock version "
                                    + lockfile.version()
                                    + " contains external package `"
                                    + lockPackage.packageId()
                                    + ":"
                                    + lockPackage.version()
                                    + "` without member attribution.",
                            "Run `zolt resolve --workspace` with this Zolt version to enrich the lock with member-qualified package, export, and optional-boundary evidence."));
                });
    }
}
