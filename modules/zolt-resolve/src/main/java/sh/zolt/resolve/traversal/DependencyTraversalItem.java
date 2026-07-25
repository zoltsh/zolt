package sh.zolt.resolve.traversal;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.maven.Coordinate;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.request.DependencyExclusion;
import sh.zolt.resolve.request.DependencyRequest;
import java.util.List;
import java.util.Optional;

record DependencyTraversalItem(
        Optional<PackageNode> parent,
        DependencyRequest request,
        DependencyScope sourceScope,
        List<DependencyExclusion> edgeExclusions,
        DependencyTraversalDecision decision) {
    DependencyTraversalItem {
        parent = parent == null ? Optional.empty() : parent;
        edgeExclusions = List.copyOf(edgeExclusions);
    }

    static DependencyTraversalItem direct(DependencyRequest request) {
        return new DependencyTraversalItem(
                Optional.empty(),
                request,
                request.scope(),
                request.exclusions(),
                DependencyTraversalDecision.include("direct dependency"));
    }

    static DependencyTraversalItem transitive(
            PackageNode parent,
            DependencyRequest request,
            DependencyScope sourceScope,
            List<DependencyExclusion> edgeExclusions,
            DependencyTraversalDecision decision) {
        return new DependencyTraversalItem(Optional.of(parent), request, sourceScope, edgeExclusions, decision);
    }

    static DependencyTraversalItem transitive(
            PackageNode parent,
            DependencyRequest request,
            List<DependencyExclusion> edgeExclusions,
            DependencyTraversalDecision decision) {
        return transitive(parent, request, request.scope(), edgeExclusions, decision);
    }

    List<DependencyExclusion> matchingExclusions(Coordinate coordinate) {
        return edgeExclusions.stream()
                .filter(exclusion -> exclusion.matches(coordinate))
                .toList();
    }
}
