package sh.zolt.resolve.graph;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.traversal.DependencyTraversalDecision;

public record ResolutionEdge(
        PackageNode from,
        PackageNode to,
        DependencyRequest request,
        DependencyScope sourceScope,
        DependencyTraversalDecision traversalDecision) {
    public ResolutionEdge(
            PackageNode from,
            PackageNode to,
            DependencyRequest request,
            DependencyTraversalDecision traversalDecision) {
        this(from, to, request, request.scope(), traversalDecision);
    }
}
