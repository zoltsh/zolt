package sh.zolt.workspace.resolve;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.resolve.ResolutionVariant;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The authoritative plain-jar artifacts supplied by workspace members.
 *
 * <p>Scope is deliberately absent from this identity: Maven scope controls graph propagation, not
 * artifact identity. A member output therefore shadows an external plain jar of the same GA in every
 * scope, while classified and typed attachments remain separate variant lanes.
 */
final class WorkspaceProvidedArtifactMediator {
    private final Map<PackageId, ProvidedArtifact> provided;

    WorkspaceProvidedArtifactMediator(Workspace workspace) {
        Map<PackageId, ProvidedArtifact> values = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            PackageId packageId = new PackageId(
                    member.config().project().group(),
                    member.config().project().name());
            values.put(packageId, new ProvidedArtifact(
                    packageId,
                    member.path(),
                    member.config().project().version()));
        }
        provided = Map.copyOf(values);
    }

    boolean shadows(LockPackage lockPackage) {
        return lockPackage.workspace().isEmpty()
                && LockArtifactVariant.of(lockPackage).isDefault()
                && provided.containsKey(lockPackage.packageId());
    }

    Optional<ProvidedArtifact> provided(PackageId packageId) {
        return Optional.ofNullable(provided.get(packageId));
    }

    Map<ResolutionVariant, String> selectedVersions() {
        Map<ResolutionVariant, String> versions = new LinkedHashMap<>();
        provided.values().stream()
                .sorted(Comparator.comparing(value -> value.packageId().toString()))
                .forEach(value -> versions.put(
                        new ResolutionVariant(
                                value.packageId(),
                                LockArtifactVariant.defaultVariant()),
                        value.version()));
        return Map.copyOf(versions);
    }

    List<LockPackage> shadowedCandidates(
            List<WorkspaceMemberResolveOutput> outputs) {
        List<LockPackage> candidates = new ArrayList<>();
        for (WorkspaceMemberResolveOutput output : outputs) {
            output.lockfile().packages().stream()
                    .filter(this::shadows)
                    .map(lockPackage -> withMember(lockPackage, output.member()))
                    .forEach(candidates::add);
        }
        return List.copyOf(candidates);
    }

    List<LockPackage> policyCandidates(
            List<WorkspaceMemberResolveOutput> outputs) {
        List<LockPackage> shadowed = shadowedCandidates(outputs);
        List<LockPackage> candidates = new ArrayList<>(shadowed);
        Map<ProvidedScope, Set<String>> members = new LinkedHashMap<>();
        for (LockPackage candidate : shadowed) {
            ProvidedArtifact target = provided.get(candidate.packageId());
            ProvidedScope key = new ProvidedScope(target, candidate.scope());
            members.computeIfAbsent(key, ignored -> new LinkedHashSet<>())
                    .addAll(candidate.members());
        }
        for (Map.Entry<ProvidedScope, Set<String>> entry : members.entrySet()) {
            candidates.add(workspaceCandidate(
                    entry.getKey().provided(),
                    entry.getKey().scope(),
                    entry.getValue()));
        }
        return List.copyOf(candidates);
    }

    List<LockConflict> conflicts(
            List<WorkspaceMemberResolveOutput> outputs) {
        return new WorkspaceExternalPackageSelector()
                .versionConflicts(policyCandidates(outputs));
    }

    private static LockPackage workspaceCandidate(
            ProvidedArtifact provided,
            DependencyScope scope,
            Set<String> members) {
        return new LockPackage(
                provided.packageId(),
                provided.version(),
                "workspace",
                scope,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(provided.member()),
                Optional.empty(),
                List.of(),
                members.stream().sorted().toList(),
                List.of(),
                List.of(),
                List.of());
    }

    private static LockPackage withMember(
            LockPackage lockPackage,
            String member) {
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

    record ProvidedArtifact(
            PackageId packageId,
            String member,
            String version) {
    }

    private record ProvidedScope(
            ProvidedArtifact provided,
            DependencyScope scope) {
    }
}
