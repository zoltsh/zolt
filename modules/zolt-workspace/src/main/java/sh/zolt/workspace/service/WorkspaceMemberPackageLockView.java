package sh.zolt.workspace.service;

import java.util.LinkedHashSet;
import java.util.Set;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.LockPackage;

/**
 * Narrows aggregate policy effects to the members that make a package visible in one package plan.
 */
final class WorkspaceMemberPackageLockView {
    private WorkspaceMemberPackageLockView() {
    }

    static LockPackage forVisibleMembers(
            LockPackage lockPackage,
            Set<String> visibleMembers,
            LockMemberGraphIndex memberGraphs) {
        if (lockPackage.members().isEmpty()) {
            return lockPackage;
        }
        Set<String> policies = new LinkedHashSet<>();
        java.util.List<String> members = lockPackage.members().stream()
                .filter(visibleMembers::contains)
                .toList();
        java.util.List<String> exportedBy = lockPackage.exportedBy().stream()
                .filter(visibleMembers::contains)
                .toList();
        lockPackage.members().stream()
                .filter(visibleMembers::contains)
                .map(member ->
                        memberGraphs.policiesFor(member, lockPackage))
                .forEach(policies::addAll);
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
                members,
                exportedBy,
                policies.stream().sorted().toList(),
                lockPackage.toolGroups());
    }
}
