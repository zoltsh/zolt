package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.LockPackage;
import sh.zolt.workspace.service.Workspace;
import java.util.ArrayList;
import java.util.List;

final class WorkspaceMediationCandidates {
    private WorkspaceMediationCandidates() {
    }

    static List<LockPackage> from(
            Workspace workspace,
            List<WorkspaceMemberResolveOutput> memberOutputs) {
        WorkspaceProvidedArtifactMediator provided =
                new WorkspaceProvidedArtifactMediator(workspace);
        List<LockPackage> candidates = new ArrayList<>();
        for (WorkspaceMemberResolveOutput output : memberOutputs) {
            for (LockPackage lockPackage : output.lockfile().packages()) {
                if (!provided.shadows(output.member(), lockPackage)) {
                    LockPackage candidate = withMember(lockPackage, output.member());
                    candidates.add(candidate);
                }
            }
        }
        return List.copyOf(candidates);
    }

    private static LockPackage withMember(LockPackage lockPackage, String member) {
        return new LockPackage(
                lockPackage.packageId(),
                lockPackage.version(),
                lockPackage.source(),
                lockPackage.scope(),
                lockPackage.direct(),
                lockPackage.jar(),
                lockPackage.pom(),
                lockPackage.jarSha256(),
                lockPackage.pomSha256(),
                lockPackage.artifact(),
                lockPackage.artifactType(),
                lockPackage.artifactSha256(),
                lockPackage.workspace(),
                lockPackage.workspaceOutput(),
                lockPackage.dependencies(),
                List.of(member),
                lockPackage.exportedBy(),
                lockPackage.policies(),
                lockPackage.toolGroups());
    }

}
