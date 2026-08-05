package sh.zolt.workspace.service;

import sh.zolt.workspace.state.WorkspaceMemberState;
import sh.zolt.workspace.state.WorkspaceState;
import java.util.List;
import java.util.Map;
import java.util.Optional;

record WorkspaceDirtyPlan(
        WorkspaceState previousState,
        Map<String, MemberPlan> members) {
    WorkspaceDirtyPlan {
        members = Map.copyOf(members);
    }

    MemberPlan member(String member) {
        return members.get(member);
    }

    int dirtyMemberCount() {
        return (int) members.values().stream()
                .filter(MemberPlan::buildRequired)
                .count();
    }

    record MemberPlan(
            WorkspaceMemberState candidateState,
            Optional<WorkspaceMemberState> previousState,
            int sourceCount,
            List<WorkspaceDirtyReason> reasons) {
        MemberPlan {
            previousState = previousState == null ? Optional.empty() : previousState;
            reasons = List.copyOf(reasons);
        }

        boolean buildRequired() {
            return reasons.stream().anyMatch(WorkspaceDirtyReason::requiresPipeline);
        }

        boolean finalizeRequired() {
            return reasons.stream().anyMatch(WorkspaceDirtyReason::requiresFinalization);
        }

        boolean testCompileRequired() {
            return buildRequired()
                    || reasons.stream().anyMatch(WorkspaceDirtyReason::requiresTestCompile);
        }

        String previousCompileAbiDigest() {
            return previousState
                    .map(WorkspaceMemberState::compileAbiDigest)
                    .orElse("");
        }
    }
}
