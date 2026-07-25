package sh.zolt.workspace.resolve;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.resolve.ResolveException;
import sh.zolt.workspace.service.Workspace;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class WorkspaceMediationCandidates {
    private WorkspaceMediationCandidates() {
    }

    static List<LockPackage> from(
            Workspace workspace,
            List<WorkspaceMemberResolveOutput> memberOutputs) {
        Set<ProvidedArtifact> provided = new LinkedHashSet<>();
        workspace.edges().forEach(edge -> provided.add(new ProvidedArtifact(
                packageId(edge.coordinate()),
                LockArtifactVariant.defaultVariant(),
                scope(edge.scope()))));
        List<LockPackage> candidates = new ArrayList<>();
        for (WorkspaceMemberResolveOutput output : memberOutputs) {
            for (LockPackage lockPackage : output.lockfile().packages()) {
                if (!provided.contains(new ProvidedArtifact(
                        lockPackage.packageId(),
                        LockArtifactVariant.of(lockPackage),
                        lockPackage.scope()))) {
                    candidates.add(withMember(lockPackage, output.member()));
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

    private static DependencyScope scope(String scope) {
        return switch (scope) {
            case "compile" -> DependencyScope.COMPILE;
            case "test" -> DependencyScope.TEST;
            case "processor" -> DependencyScope.PROCESSOR;
            case "test-processor" -> DependencyScope.TEST_PROCESSOR;
            default -> throw new ResolveException(
                    "Unsupported workspace dependency scope `" + scope + "`.");
        };
    }

    private static PackageId packageId(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        return new PackageId(parts[0], parts[1]);
    }

    private record ProvidedArtifact(
            PackageId packageId,
            LockArtifactVariant variant,
            DependencyScope scope) {
    }
}
