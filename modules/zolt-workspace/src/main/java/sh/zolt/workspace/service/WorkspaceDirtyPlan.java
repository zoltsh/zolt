package sh.zolt.workspace.service;

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
            boolean buildRequired,
            List<String> reasons) {
        MemberPlan {
            previousState = previousState == null ? Optional.empty() : previousState;
            reasons = List.copyOf(reasons);
        }

        String previousCompileAbiDigest() {
            return previousState
                    .map(WorkspaceMemberState::compileAbiDigest)
                    .orElse("");
        }
    }
}
