package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Decides which members stage 1's scheduler is allowed to see at all. */
final class WorkspaceMemberAdmission {
    private WorkspaceMemberAdmission() {
    }

    /**
     * Everything stage 0 flagged, plus every member downstream of one that will actually be rebuilt,
     * because a rebuild can change an ABI its dependents compile against. Dependents that turn out
     * not to be invalidated cost a queue slot and nothing else. A member admitted only to have its
     * outputs finalized never recompiles, so it cannot move an ABI and does not drag its dependents
     * in.
     *
     * <p>Every member on the frontier came from the batch plan's own member list or from its
     * dependents map, so the plan always knows it. If that ever stops being true the member is one
     * stage 0 said needs building, and quietly dropping it would ship its stale output — so it is an
     * error rather than a {@code continue}.
     */
    static List<String> order(
            WorkspaceBuildBatchPlanner.Plan plan,
            WorkspaceDirtyPlan dirtyPlan) {
        Set<String> admitted = new LinkedHashSet<>();
        Set<String> expanded = new LinkedHashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        for (String member : plan.includedMembers()) {
            WorkspaceDirtyPlan.MemberPlan memberPlan = dirtyPlan.member(member);
            if (memberPlan.buildRequired()) {
                frontier.addLast(member);
            } else if (memberPlan.finalizeRequired()) {
                admitted.add(member);
            }
        }
        while (!frontier.isEmpty()) {
            String member = frontier.removeFirst();
            List<String> dependents = plan.dependentsByDependency().get(member);
            if (dependents == null) {
                throw new BuildException(
                        "Workspace member `"
                                + member
                                + "` needs to be rebuilt but is missing from the workspace build"
                                + " plan's dependency graph, so it cannot be scheduled. Run `zolt"
                                + " resolve --workspace` to regenerate the workspace graph.");
            }
            admitted.add(member);
            if (expanded.add(member)) {
                frontier.addAll(dependents);
            }
        }
        return plan.includedMembers().stream().filter(admitted::contains).toList();
    }
}
