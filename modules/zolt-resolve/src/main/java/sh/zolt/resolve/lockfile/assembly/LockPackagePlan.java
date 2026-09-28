package sh.zolt.resolve.lockfile.assembly;

import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.selection.SelectedDependencyScope;
import sh.zolt.resolve.version.VersionSelectionResult;
import java.util.List;
import java.util.Optional;

/**
 * One planned lock package: a selected node under a single scope, its artifact descriptor, the graph its
 * dependency edges are read from (the main graph or an isolated tool graph), and the exec tool groups it
 * belongs to (empty for main and Groovy compiler packages).
 */
record LockPackagePlan(
        PackageNode node,
        SelectedDependencyScope selectedScope,
        ArtifactDescriptor artifactDescriptor,
        ResolutionGraph graph,
        VersionSelectionResult selection,
        List<String> toolGroups) {
    static LockPackagePlan of(
            PackageNode node,
            SelectedDependencyScope selectedScope,
            ResolutionGraph graph,
            VersionSelectionResult selection,
            List<String> toolGroups) {
        Coordinate coordinate = new Coordinate(
                node.packageId().groupId(), node.packageId().artifactId(), Optional.of(node.selectedVersion()));
        ArtifactDescriptor descriptor = selectedScope.artifactDescriptor()
                .map(value -> new ArtifactDescriptor(coordinate, value.classifier(), value.extension()))
                .orElseGet(() -> ArtifactDescriptor.jar(coordinate));
        return new LockPackagePlan(node, selectedScope, descriptor, graph, selection, toolGroups);
    }
}
