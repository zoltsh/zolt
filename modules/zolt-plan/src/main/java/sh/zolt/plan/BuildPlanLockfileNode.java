package sh.zolt.plan;

import java.util.List;

/**
 * The {@code lockfile} plan node for the authoritative lockfile the request named.
 *
 * <p>A member-directory plan reads the workspace root's lock, so the node labels its input as the
 * workspace lock rather than leaking a {@code ../..} relative path into plan output (design §6.9).
 */
final class BuildPlanLockfileNode {
    private BuildPlanLockfileNode() {
    }

    static PlanNode node(BuildPlanLockfileState lockfile, boolean workspaceLockfile) {
        String lockfileInput = workspaceLockfile ? "zolt.lock (workspace)" : "zolt.lock";
        if (lockfile.error().isPresent()) {
            return new PlanNode(
                    "lockfile",
                    "resolve",
                    PlanNodeStatus.BLOCKED,
                    "Existing zolt.lock is not readable by this Zolt version.",
                    List.of("zolt.toml", lockfileInput),
                    List.of(),
                    List.of(),
                    List.of(new PlanBlocker(
                            "invalid-lockfile",
                            lockfile.error().orElseThrow(),
                            "Run `zolt resolve` to regenerate zolt.lock, then rerun `zolt plan`.")));
        }
        if (lockfile.present()) {
            return new PlanNode(
                    "lockfile",
                    "resolve",
                    PlanNodeStatus.READY,
                    "Read existing zolt.lock without refreshing dependency metadata.",
                    List.of("zolt.toml", lockfileInput),
                    List.of(),
                    List.of("freshness: verify with `zolt resolve --locked` or `zolt check --check lockfile`"),
                    List.of());
        }
        return new PlanNode(
                "lockfile",
                "resolve",
                PlanNodeStatus.BLOCKED,
                "Dependency graph is not locked yet.",
                List.of("zolt.toml"),
                List.of(lockfileInput),
                List.of(),
                List.of(new PlanBlocker(
                        "missing-lockfile",
                        "zolt.lock is missing; plan will not resolve or download artifacts.",
                        "Run `zolt resolve` first, then rerun `zolt plan`.")));
    }
}
