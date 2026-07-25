package sh.zolt.workspace.resolve;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
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
            List<LockPackage> outputCandidates = new ArrayList<>();
            for (LockPackage lockPackage : output.lockfile().packages()) {
                if (!provided.contains(new ProvidedArtifact(
                        lockPackage.packageId(),
                        LockArtifactVariant.of(lockPackage),
                        lockPackage.scope()))) {
                    LockPackage candidate = withMember(lockPackage, output.member());
                    candidates.add(candidate);
                    outputCandidates.add(candidate);
                }
            }
            addConflictRequests(candidates, outputCandidates, output.lockfile().conflicts(), output.member());
        }
        return List.copyOf(candidates);
    }

    private static void addConflictRequests(
            List<LockPackage> candidates,
            List<LockPackage> outputCandidates,
            List<LockConflict> conflicts,
            String member) {
        for (LockConflict conflict : conflicts) {
            LockArtifactVariant variant =
                    conflict.variant().orElseGet(LockArtifactVariant::defaultVariant);
            List<LockPackage> templates = outputCandidates.stream()
                    .filter(candidate -> candidate.packageId().equals(conflict.packageId()))
                    .filter(candidate -> LockArtifactVariant.of(candidate).equals(variant))
                    .toList();
            for (LockPackage template : templates) {
                for (String requestedVersion : conflict.requestedVersions()) {
                    boolean represented = outputCandidates.stream()
                            .anyMatch(candidate -> candidate.packageId().equals(conflict.packageId())
                                    && LockArtifactVariant.of(candidate).equals(variant)
                                    && candidate.scope() == template.scope()
                                    && candidate.version().equals(requestedVersion));
                    if (!represented) {
                        candidates.add(requestCandidate(
                                template, requestedVersion, member));
                    }
                }
            }
        }
    }

    private static LockPackage requestCandidate(
            LockPackage template,
            String version,
            String member) {
        return new LockPackage(
                template.packageId(),
                version,
                template.source(),
                template.scope(),
                false,
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                List.of(),
                List.of(member),
                List.of(),
                List.of(),
                List.of());
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
