package sh.zolt.resolve.traversal;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.request.DependencyExclusion;
import java.util.List;

record DependencyTraversalVisitKey(
        PackageId packageId,
        String version,
        LockArtifactVariant variant,
        DependencyScope scope,
        List<DependencyExclusion> activeExclusions)
        implements Comparable<DependencyTraversalVisitKey> {
    DependencyTraversalVisitKey {
        activeExclusions = List.copyOf(activeExclusions);
    }

    static DependencyTraversalVisitKey from(
            PackageNode node,
            DependencyScope scope,
            List<DependencyExclusion> activeExclusions) {
        return new DependencyTraversalVisitKey(
                node.packageId(),
                node.selectedVersion(),
                node.variant(),
                scope,
                activeExclusions);
    }

    @Override
    public int compareTo(DependencyTraversalVisitKey other) {
        int packageCompared = packageId.toString().compareTo(other.packageId.toString());
        if (packageCompared != 0) {
            return packageCompared;
        }
        int versionCompared = version.compareTo(other.version);
        if (versionCompared != 0) {
            return versionCompared;
        }
        int variantCompared = variant.compareTo(other.variant);
        if (variantCompared != 0) {
            return variantCompared;
        }
        int scopeCompared = scope.compareTo(other.scope);
        if (scopeCompared != 0) {
            return scopeCompared;
        }
        return exclusionKey(activeExclusions).compareTo(exclusionKey(other.activeExclusions));
    }

    private static String exclusionKey(List<DependencyExclusion> exclusions) {
        return String.join(",", exclusions.stream()
                .map(exclusion -> exclusion.groupId() + ":" + exclusion.artifactId())
                .toList());
    }
}
