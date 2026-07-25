package sh.zolt.workspace.resolve;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.resolve.ResolutionVariant;

/**
 * Selects the top-most cross-member overrides for one materialization pass.
 *
 * <p>A child selected from a soon-to-be-discarded parent graph is not stable evidence. Applying only
 * the ancestor frontier lets the resolver materialize the selected parent before the workspace
 * considers its children again.
 */
final class WorkspaceMediationFrontier {
    private WorkspaceMediationFrontier() {
    }

    static Map<ResolutionVariant, String> overrides(
            List<WorkspaceMemberResolveOutput> outputs,
            Map<ResolutionVariant, String> selectedVersions,
            WorkspaceProvidedArtifactMediator provided) {
        Set<ResolutionVariant> mismatched =
                mismatched(outputs, selectedVersions, provided);
        Set<ResolutionVariant> descendants =
                mismatchedDescendants(outputs, mismatched);
        Map<ResolutionVariant, String> frontier = new LinkedHashMap<>();
        mismatched.stream()
                .filter(variant -> !descendants.contains(variant))
                .sorted(Comparator.comparing(ResolutionVariant::toString))
                .forEach(variant -> frontier.put(
                        variant, selectedVersions.get(variant)));
        return frontier.isEmpty() && !mismatched.isEmpty()
                ? Map.copyOf(selectedVersions)
                : Map.copyOf(frontier);
    }

    private static Set<ResolutionVariant> mismatched(
            List<WorkspaceMemberResolveOutput> outputs,
            Map<ResolutionVariant, String> selectedVersions,
            WorkspaceProvidedArtifactMediator provided) {
        Set<ResolutionVariant> mismatched = new LinkedHashSet<>();
        for (WorkspaceMemberResolveOutput output : outputs) {
            output.lockfile().packages().stream()
                    .filter(lockPackage -> !provided.shadows(output.member(), lockPackage))
                    .filter(lockPackage -> {
                        String selected = selectedVersions.get(variant(lockPackage));
                        return selected != null && !selected.equals(lockPackage.version());
                    })
                    .map(WorkspaceMediationFrontier::variant)
                    .forEach(mismatched::add);
        }
        return mismatched;
    }

    private static Set<ResolutionVariant> mismatchedDescendants(
            List<WorkspaceMemberResolveOutput> outputs,
            Set<ResolutionVariant> mismatched) {
        Set<ResolutionVariant> descendants = new LinkedHashSet<>();
        for (WorkspaceMemberResolveOutput output : outputs) {
            LockDependencyIndex index =
                    new LockDependencyIndex(output.lockfile().packages());
            output.lockfile().packages().stream()
                    .filter(lockPackage -> mismatched.contains(variant(lockPackage)))
                    .forEach(root -> collectDescendants(
                            root, index, mismatched, descendants));
        }
        return descendants;
    }

    private static void collectDescendants(
            LockPackage root,
            LockDependencyIndex index,
            Set<ResolutionVariant> mismatched,
            Set<ResolutionVariant> descendants) {
        ArrayDeque<LockPackage> queue = new ArrayDeque<>();
        dependencies(root, index).forEach(queue::addLast);
        Set<String> visited = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            LockPackage candidate = queue.removeFirst();
            if (!visited.add(ref(candidate))) {
                continue;
            }
            ResolutionVariant variant = variant(candidate);
            if (mismatched.contains(variant)) {
                descendants.add(variant);
            }
            dependencies(candidate, index).forEach(queue::addLast);
        }
    }

    private static List<LockPackage> dependencies(
            LockPackage lockPackage,
            LockDependencyIndex index) {
        return lockPackage.dependencies().stream()
                .map(edge -> index.resolveGraphEdge(
                        edge, "zolt resolve --workspace").orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    private static ResolutionVariant variant(LockPackage lockPackage) {
        return new ResolutionVariant(
                lockPackage.packageId(), LockArtifactVariant.of(lockPackage));
    }

    private static String ref(LockPackage lockPackage) {
        return lockPackage.packageId()
                + ":"
                + lockPackage.version()
                + ":"
                + lockPackage.scope().lockfileName()
                + ":"
                + LockArtifactVariant.of(lockPackage).key();
    }
}
