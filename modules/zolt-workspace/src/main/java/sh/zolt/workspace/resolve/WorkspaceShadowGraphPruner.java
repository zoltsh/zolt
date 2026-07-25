package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockPackage;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Removes the displaced external workspace artifact and any closure reachable only through it. */
final class WorkspaceShadowGraphPruner {
    private WorkspaceShadowGraphPruner() {
    }

    static List<LockPackage> reachableExternalPackages(
            WorkspaceMemberResolveOutput output,
            WorkspaceProvidedArtifactMediator provided) {
        List<LockPackage> packages = output.lockfile().packages();
        LockDependencyIndex index = new LockDependencyIndex(packages);
        Set<String> referenced = new LinkedHashSet<>();
        for (LockPackage lockPackage : packages) {
            for (String dependency : lockPackage.dependencies()) {
                index.resolveGraphEdge(dependency, "zolt resolve --workspace")
                        .map(LockDependencyEdge::of)
                        .map(LockDependencyEdge::encode)
                        .ifPresent(referenced::add);
            }
        }
        ArrayDeque<LockPackage> queue = new ArrayDeque<>();
        packages.stream()
                .filter(lockPackage -> !provided.shadows(lockPackage))
                .filter(lockPackage -> lockPackage.direct()
                        || !referenced.contains(
                                LockDependencyEdge.of(lockPackage).encode()))
                .forEach(queue::addLast);
        Map<String, LockPackage> reached = new LinkedHashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            LockPackage current = queue.removeFirst();
            String ref = LockDependencyEdge.of(current).encode();
            if (!visited.add(ref)) {
                continue;
            }
            reached.put(ref, withRewrittenWorkspaceEdges(current, provided));
            for (String dependency : current.dependencies()) {
                LockDependencyEdge.parse(dependency)
                        .filter(edge -> provided.provided(edge.packageId()).isPresent()
                                && edge.variant().isDefault())
                        .ifPresentOrElse(
                                ignored -> {
                                    // The workspace output is the terminal graph target. Its own graph is
                                    // represented by the provider member, not the displaced external POM.
                                },
                                () -> index.resolveGraphEdge(
                                                dependency,
                                                "zolt resolve --workspace")
                                        .filter(candidate -> !provided.shadows(candidate))
                                        .filter(candidate -> !visited.contains(
                                                LockDependencyEdge.of(candidate).encode()))
                                        .ifPresent(queue::addLast));
            }
        }
        return List.copyOf(reached.values());
    }

    private static LockPackage withRewrittenWorkspaceEdges(
            LockPackage lockPackage,
            WorkspaceProvidedArtifactMediator provided) {
        List<String> dependencies = lockPackage.dependencies().stream()
                .map(dependency -> rewrite(dependency, provided))
                .sorted()
                .toList();
        if (dependencies.equals(lockPackage.dependencies())) {
            return lockPackage;
        }
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
                dependencies,
                lockPackage.members(),
                lockPackage.exportedBy(),
                lockPackage.policies(),
                lockPackage.toolGroups());
    }

    private static String rewrite(
            String dependency,
            WorkspaceProvidedArtifactMediator provided) {
        return LockDependencyEdge.parse(dependency)
                .filter(edge -> edge.variant().isDefault())
                .flatMap(edge -> provided.provided(edge.packageId())
                        .map(target -> edge.scope()
                                .map(scope -> LockDependencyEdge.encode(
                                        edge.packageId(),
                                        target.version(),
                                        edge.variant(),
                                        scope))
                                .orElseGet(() -> LockDependencyEdge.encode(
                                        edge.packageId(),
                                        target.version(),
                                        edge.variant()))))
                .orElse(dependency);
    }
}
