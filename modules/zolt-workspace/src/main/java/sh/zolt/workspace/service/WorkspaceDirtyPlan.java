package sh.zolt.workspace.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /** Members stage 0 could not leave alone: they need the pipeline or a clean-output assurance. */
    Set<String> workRequiredMembers() {
        Set<String> required = new LinkedHashSet<>();
        members.forEach((member, plan) -> {
            if (plan.buildRequired() || plan.finalizeRequired()) {
                required.add(member);
            }
        });
        return required;
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

        List<String> reasonNames() {
            return reasons.stream().map(WorkspaceDirtyReason::wireName).toList();
        }

        String previousCompileAbiDigest() {
            return previousState
                    .map(WorkspaceMemberState::compileAbiDigest)
                    .orElse("");
        }
    }
}
