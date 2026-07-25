package sh.zolt.resolve.traversal;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.maven.Coordinate;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.request.DependencyExclusion;
import sh.zolt.resolve.request.DependencyRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

record DependencyTraversalItem(
        Optional<PackageNode> parent,
        DependencyRequest request,
        String materializedVersion,
        DependencyScope sourceScope,
        List<DependencyExclusion> activeExclusions,
        DependencyTraversalDecision decision) {
    DependencyTraversalItem {
        parent = parent == null ? Optional.empty() : parent;
        materializedVersion = materializedVersion == null || materializedVersion.isBlank()
                ? request.requestedVersion()
                : materializedVersion;
        activeExclusions = normalizedExclusions(activeExclusions);
    }

    static DependencyTraversalItem direct(DependencyRequest request) {
        return direct(request, request.requestedVersion());
    }

    static DependencyTraversalItem direct(
            DependencyRequest request,
            String materializedVersion) {
        return new DependencyTraversalItem(
                Optional.empty(),
                request,
                materializedVersion,
                request.scope(),
                request.exclusions(),
                DependencyTraversalDecision.include("direct dependency"));
    }

    static DependencyTraversalItem transitive(
            PackageNode parent,
            DependencyRequest request,
            String materializedVersion,
            DependencyScope sourceScope,
            List<DependencyExclusion> activeExclusions,
            DependencyTraversalDecision decision) {
        return new DependencyTraversalItem(
                Optional.of(parent),
                request,
                materializedVersion,
                sourceScope,
                activeExclusions,
                decision);
    }

    static DependencyTraversalItem transitive(
            PackageNode parent,
            DependencyRequest request,
            DependencyScope sourceScope,
            List<DependencyExclusion> activeExclusions,
            DependencyTraversalDecision decision) {
        return transitive(
                parent,
                request,
                request.requestedVersion(),
                sourceScope,
                activeExclusions,
                decision);
    }

    static DependencyTraversalItem transitive(
            PackageNode parent,
            DependencyRequest request,
            List<DependencyExclusion> activeExclusions,
            DependencyTraversalDecision decision) {
        return transitive(parent, request, request.scope(), activeExclusions, decision);
    }

    List<DependencyExclusion> matchingExclusions(Coordinate coordinate) {
        return activeExclusions.stream()
                .filter(exclusion -> exclusion.matches(coordinate))
                .toList();
    }

    List<DependencyExclusion> including(List<DependencyExclusion> declaredExclusions) {
        List<DependencyExclusion> cumulative = new ArrayList<>(activeExclusions);
        cumulative.addAll(declaredExclusions);
        return normalizedExclusions(cumulative);
    }

    private static List<DependencyExclusion> normalizedExclusions(
            List<DependencyExclusion> exclusions) {
        return exclusions.stream()
                .distinct()
                .sorted(Comparator.comparing(exclusion ->
                        exclusion.groupId() + ":" + exclusion.artifactId()))
                .toList();
    }
}
