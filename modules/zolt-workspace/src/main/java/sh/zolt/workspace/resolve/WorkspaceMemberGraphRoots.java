package sh.zolt.workspace.resolve;

import java.util.List;
import sh.zolt.lockfile.LockDependencyGraphException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.member.MemberResolvedViewService;
import sh.zolt.workspace.service.Workspace;

/**
 * Computes the exact locked roots of one member's dependency graph, for the workspace-shaped reports
 * that hold a member path and a config rather than a
 * {@link sh.zolt.workspace.member.MemberResolvedView}.
 *
 * <p>The selection rule itself lives in {@link MemberResolvedViewService#graphRoots}, which is also
 * what a member's own view answers, so a member's roots cannot depend on which entry point asked.
 */
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
        return MemberResolvedViewService.graphRoots(
                projection.project(memberPath, effectiveConfig, aggregate, workspace),
                aggregate);
    }

}
