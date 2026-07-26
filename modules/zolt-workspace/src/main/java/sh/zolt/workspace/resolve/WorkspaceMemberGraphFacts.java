package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockPackage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Completes member-qualified graph facts and marks optional dependency closures. */
final class WorkspaceMemberGraphFacts {
    private WorkspaceMemberGraphFacts() {
    }

    static List<LockMemberGraph> complete(
            WorkspaceExternalSelection selection,
            List<WorkspaceMemberResolveOutput> outputs) {
        Map<String, WorkspaceMemberResolveOutput> outputsByMember =
                new LinkedHashMap<>();
        outputs.forEach(output -> outputsByMember.put(output.member(), output));
        Map<Key, LockMemberGraph> existing = new LinkedHashMap<>();
        selection.memberGraphs().forEach(graph ->
                existing.put(new Key(graph.member(), packageKey(graph)), graph));
        List<LockMemberGraph> completed = new ArrayList<>();
        for (LockPackage lockPackage : selection.packages()) {
            if (lockPackage.workspace().isPresent()) {
                continue;
            }
            PackageKey packageKey = packageKey(lockPackage);
            boolean hasQualifiedFacts = existing.keySet().stream()
                    .anyMatch(key -> key.packageKey().equals(packageKey));
            boolean hasOptionalMember = lockPackage.members().stream()
                    .map(outputsByMember::get)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(output -> output.optionalPackages().contains(
                            optionalIdentity(lockPackage)));
            boolean hasDeclaredOptionalMember = lockPackage.members().stream()
                    .map(outputsByMember::get)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(output -> output.declaredOptionalPackages().contains(
                            optionalIdentity(lockPackage)));
            if (!hasQualifiedFacts && !hasOptionalMember && !hasDeclaredOptionalMember) {
                continue;
            }
            for (String member : lockPackage.members()) {
                LockMemberGraph graph = existing.get(
                        new Key(member, packageKey));
                WorkspaceMemberResolveOutput output = outputsByMember.get(member);
                boolean declaredOptional = output != null
                        && output.declaredOptionalPackages().contains(
                                optionalIdentity(lockPackage));
                boolean optionalOnly = output != null
                        && output.optionalPackages().contains(
                                optionalIdentity(lockPackage));
                completed.add(new LockMemberGraph(
                        member,
                        lockPackage.packageId(),
                        lockPackage.version(),
                        LockArtifactVariant.of(lockPackage),
                        lockPackage.scope(),
                        graph == null
                                ? lockPackage.dependencies()
                                : graph.dependencies(),
                        graph == null
                                ? lockPackage.policies()
                                : graph.policies(),
                        declaredOptional,
                        optionalOnly));
            }
        }
        return List.copyOf(completed);
    }

    private static WorkspaceOptionalPackage optionalIdentity(
            LockPackage lockPackage) {
        return new WorkspaceOptionalPackage(
                lockPackage.packageId(),
                LockArtifactVariant.of(lockPackage),
                lockPackage.scope());
    }

    private static PackageKey packageKey(LockMemberGraph graph) {
        return new PackageKey(
                graph.packageId(),
                graph.version(),
                graph.variant(),
                graph.scope());
    }

    private static PackageKey packageKey(LockPackage lockPackage) {
        return new PackageKey(
                lockPackage.packageId(),
                lockPackage.version(),
                LockArtifactVariant.of(lockPackage),
                lockPackage.scope());
    }

    private record Key(
            String member,
            PackageKey packageKey) {
    }

    private record PackageKey(
            sh.zolt.dependency.PackageId packageId,
            String version,
            LockArtifactVariant variant,
            sh.zolt.dependency.DependencyScope scope) {
    }
}
