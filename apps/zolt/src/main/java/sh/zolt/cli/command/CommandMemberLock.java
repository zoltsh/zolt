package sh.zolt.cli.command;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.workspace.service.WorkspaceMemberLockProjector;

/**
 * The lock a read-only command should describe.
 *
 * <p>Design §4.5: a member's answer is its SELECTION out of the one root lock, never the whole of it.
 * A report that hands the complete workspace lock to a single-project formatter is wrong in a way
 * that reads as right — it produces a plausible, complete-looking list that includes packages the
 * member cannot see. This returns the projection instead, and a standalone project's own lock
 * unchanged.
 */
public final class CommandMemberLock {
    private CommandMemberLock() {
    }

    public static ZoltLockfile authoritative(ProjectCommandContext context) {
        ZoltLockfile lockfile = new ZoltLockfileReader().read(context.lockfilePath());
        if (!context.workspaceMember()) {
            return lockfile;
        }
        return new WorkspaceMemberLockProjector()
                .projectLock(lockfile, context.memberPath(), context.lockRoot());
    }
}
