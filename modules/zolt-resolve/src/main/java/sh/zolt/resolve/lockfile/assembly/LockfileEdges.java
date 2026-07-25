package sh.zolt.resolve.lockfile.assembly;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.graph.ResolutionEdge;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.version.VersionSelectionResult;
import java.util.List;
import java.util.Optional;

/**
 * Emits the scope- and variant-qualified dependency-edge refs stored in a lock package's
 * {@code dependencies}. The target GAV comes from the resolved {@code to} node; scope and artifact
 * descriptor come from the resolved edge request, where both identities are still authoritative.
 */
final class LockfileEdges {
    private LockfileEdges() {
    }

    static List<String> dependenciesFor(
            PackageNode node,
            DependencyScope sourceScope,
            ResolutionGraph graph,
            VersionSelectionResult selection) {
        return graph.edges().stream()
                .filter(edge -> edge.from().equals(node))
                .filter(edge -> edge.sourceScope() == sourceScope)
                .map(edge -> edgeRef(edge, selection))
                .distinct()
                .sorted()
                .toList();
    }

    private static String edgeRef(ResolutionEdge edge, VersionSelectionResult selection) {
        LockArtifactVariant variant = edge.request().artifactDescriptor()
                .map(descriptor -> new LockArtifactVariant(descriptor.extension(), descriptor.classifier()))
                .orElseGet(() -> new LockArtifactVariant("jar", Optional.empty()));
        String selectedVersion = selection.selectedNodes().stream()
                .filter(node -> node.packageId().equals(edge.to().packageId()))
                .filter(node -> node.variant().equals(variant))
                .map(PackageNode::selectedVersion)
                .findFirst()
                .orElse(edge.to().selectedVersion());
        return LockDependencyEdge.encode(
                edge.to().packageId(), selectedVersion, variant, edge.request().scope());
    }
}
