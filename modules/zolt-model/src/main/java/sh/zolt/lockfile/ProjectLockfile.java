package sh.zolt.lockfile;

import java.nio.file.Path;

/**
 * The one place that turns a lock ROOT into a lockfile path.
 *
 * <p>Design §4.5/§6.8: a workspace has exactly one authoritative {@code zolt.lock}, at its root, and
 * no command creates or consumes a member-local one. The bug that rule keeps out is not spelling the
 * file name wrong — it is resolving the right name against the wrong directory, so a command started
 * in {@code apps/api} silently reads or writes {@code apps/api/zolt.lock}.
 *
 * <p>The parameter is therefore named {@code lockRoot}, not {@code projectDirectory}: callers must
 * have decided which directory owns the lock before they can ask for the path. The command boundary
 * decides that once, in {@code ProjectCommandContext}, and hands the answer down; the arch guardrail
 * {@code LockfilePathOwnershipGuardrailTest} keeps every other class from re-deriving it.
 */
public final class ProjectLockfile {
    /** The lockfile's file name. */
    public static final String NAME = "zolt.lock";

    private ProjectLockfile() {
    }

    /** The lockfile inside {@code lockRoot} — a workspace root, or a standalone project directory. */
    public static Path in(Path lockRoot) {
        return lockRoot.resolve(NAME);
    }
}
