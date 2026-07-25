package sh.zolt.resolve.graph;

import sh.zolt.resolve.DependencyPolicyEffect;
import sh.zolt.resolve.version.VersionConflict;
import java.util.List;

public record ResolutionGraph(
        List<PackageNode> nodes,
        List<ResolutionEdge> edges,
        List<VersionConflict> conflicts,
        List<DependencyPolicyEffect> policyEffects,
        List<ResolutionReachability> reachability) {
    public ResolutionGraph(
            List<PackageNode> nodes,
            List<ResolutionEdge> edges,
            List<VersionConflict> conflicts,
            List<DependencyPolicyEffect> policyEffects) {
        this(nodes, edges, conflicts, policyEffects, List.of());
    }

    public ResolutionGraph(
            List<PackageNode> nodes,
            List<ResolutionEdge> edges,
            List<VersionConflict> conflicts) {
        this(nodes, edges, conflicts, List.of(), List.of());
    }

    public ResolutionGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        conflicts = List.copyOf(conflicts);
        policyEffects = policyEffects == null ? List.of() : List.copyOf(policyEffects);
        reachability = reachability == null ? List.of() : List.copyOf(reachability);
    }
}
