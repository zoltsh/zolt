package sh.zolt.workspace.resolve;

import java.util.List;
import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockDependencyGraphException;
import sh.zolt.lockfile.LockGraphRootSelector;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.service.Workspace;

/** Computes the exact locked roots of one member's dependency graph. */
public final class WorkspaceMemberGraphRoots {
    private final WorkspaceMemberPolicyLockProjection projection =
            new WorkspaceMemberPolicyLockProjection();

    /**
     * Authored roots come from the member-qualified v7 records. Each otherwise-unrooted source graph
     * component contributes one deterministic resolver-injected root, including an injected cycle.
     */
    public List<String> roots(
            String memberPath,
            ProjectConfig effectiveConfig,
            ZoltLockfile aggregate,
            Workspace workspace) {
        if (aggregate.version() != ZoltLockfile.CURRENT_VERSION) {
            throw new LockDependencyGraphException(
                    "Workspace dependency graph roots require zolt.lock version "
                            + ZoltLockfile.CURRENT_VERSION + ", but found version " + aggregate.version()
                            + ". Run `zolt resolve --workspace` to regenerate the lockfile.");
        }
        ZoltLockfile memberLock = projection.project(memberPath, effectiveConfig, aggregate, workspace);
        return LockGraphRootSelector.select(
                        memberLock.packages(),
                        memberLock.dependencyRoots(),
                        aggregate.packages(),
                        "zolt resolve --workspace")
                .stream()
                .map(LockDependencyEdge::of)
                .map(LockDependencyEdge::encode)
                .sorted()
                .toList();
    }

}
