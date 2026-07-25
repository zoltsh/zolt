package sh.zolt.workspace.resolve;

import java.util.List;
import java.util.Map;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.workspace.resolve.WorkspaceExternalPackageSelector.PackageVariantKey;

/** Rewrites external edges within one variant lane without displacing an explicit workspace target. */
final class WorkspaceDependencyEdgeRewriter {
    private WorkspaceDependencyEdgeRewriter() {
    }

    static List<String> rewrite(
            List<String> dependencies,
            Map<PackageVariantKey, String> selectedVersions,
            Map<PackageVariantKey, String> protectedVersions,
            WorkspaceProvidedArtifactMediator provided,
            List<String> members,
            DependencyScope parentScope) {
        return dependencies.stream()
                .map(dependency -> rewrite(
                        dependency,
                        selectedVersions,
                        protectedVersions,
                        provided,
                        members,
                        parentScope))
                .sorted()
                .toList();
    }

    private static String rewrite(
            String dependency,
            Map<PackageVariantKey, String> selectedVersions,
            Map<PackageVariantKey, String> protectedVersions,
            WorkspaceProvidedArtifactMediator provided,
            List<String> members,
            DependencyScope parentScope) {
        return LockDependencyEdge.parse(dependency)
                .map(edge -> rewrite(
                        dependency,
                        edge,
                        selectedVersions,
                        protectedVersions,
                        provided,
                        members,
                        parentScope))
                .orElse(dependency);
    }

    private static String rewrite(
            String dependency,
            LockDependencyEdge edge,
            Map<PackageVariantKey, String> selectedVersions,
            Map<PackageVariantKey, String> protectedVersions,
            WorkspaceProvidedArtifactMediator provided,
            List<String> members,
            DependencyScope parentScope) {
        PackageVariantKey key = new PackageVariantKey(edge.packageId(), edge.variant());
        String protectedVersion = protectedVersions.get(key);
        boolean explicitWorkspaceTarget = provided != null
                && edge.variant().isDefault()
                && members.stream().anyMatch(member -> provided
                        .provided(
                                member,
                                edge.packageId(),
                                edge.scope().orElse(parentScope))
                        .filter(target -> target.version().equals(edge.version()))
                        .isPresent());
        if (explicitWorkspaceTarget
                || (protectedVersion != null && protectedVersion.equals(edge.version()))) {
            return dependency;
        }
        String selectedVersion = selectedVersions.get(key);
        if (selectedVersion == null) {
            return dependency;
        }
        return edge.scope()
                .map(scope -> LockDependencyEdge.encode(
                        edge.packageId(), selectedVersion, edge.variant(), scope))
                .orElseGet(() -> LockDependencyEdge.encode(
                        edge.packageId(), selectedVersion, edge.variant()));
    }
}
