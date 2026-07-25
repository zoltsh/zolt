package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.resolve.metrics.ResolveMetrics;
import java.util.List;

record WorkspaceMediationResult(
        List<WorkspaceMemberResolveOutput> memberOutputs,
        List<LockConflict> conflicts,
        List<LockPolicyEffect> policyEffects,
        int downloadCount,
        ResolveMetrics metrics) {
    WorkspaceMediationResult {
        memberOutputs = List.copyOf(memberOutputs);
        conflicts = List.copyOf(conflicts);
        policyEffects = List.copyOf(policyEffects);
    }
}
