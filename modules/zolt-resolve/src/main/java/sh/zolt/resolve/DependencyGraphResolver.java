package sh.zolt.resolve;

import sh.zolt.dependency.PackageId;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.metrics.ResolverMetricsSink;
import sh.zolt.resolve.metadata.DependencyMetadataSource;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.traversal.DependencyGraphTraverser;
import sh.zolt.resolve.version.VersionSelectionResult;
import sh.zolt.resolve.version.VersionSelector;
import java.util.List;
import java.util.Map;

final class DependencyGraphResolver {
    private final ResolveService.DependencyGraphTraverserFactory graphTraverserFactory;
    private final VersionSelector versionSelector;

    DependencyGraphResolver(
            ResolveService.DependencyGraphTraverserFactory graphTraverserFactory,
            VersionSelector versionSelector) {
        this.graphTraverserFactory = graphTraverserFactory;
        this.versionSelector = versionSelector;
    }

    DependencyGraphResolution resolve(
            DependencyMetadataSource metadataSource,
            DependencyPolicySettings dependencyPolicy,
            Map<PackageId, ManagedVersion> managedVersions,
            List<DependencyRequest> requests,
            ResolverMetricsSink metrics) {
        return resolve(
                metadataSource,
                dependencyPolicy,
                managedVersions,
                requests,
                metrics,
                "zolt resolve",
                SnapshotAllowance.none(),
                Map.of());
    }

    DependencyGraphResolution resolve(
            DependencyMetadataSource metadataSource,
            DependencyPolicySettings dependencyPolicy,
            Map<PackageId, ManagedVersion> managedVersions,
            List<DependencyRequest> requests,
            ResolverMetricsSink metrics,
            String retryCommand,
            SnapshotAllowance snapshotAllowance) {
        return resolve(
                metadataSource,
                dependencyPolicy,
                managedVersions,
                requests,
                metrics,
                retryCommand,
                snapshotAllowance,
                Map.of());
    }

    DependencyGraphResolution resolve(
            DependencyMetadataSource metadataSource,
            DependencyPolicySettings dependencyPolicy,
            Map<PackageId, ManagedVersion> managedVersions,
            List<DependencyRequest> requests,
            ResolverMetricsSink metrics,
            String retryCommand,
            SnapshotAllowance snapshotAllowance,
            Map<ResolutionVariant, String> versionOverrides) {
        DependencyGraphTraverser traverser = graphTraverserFactory.create(
                metadataSource,
                dependencyPolicy,
                managedVersions,
                retryCommand,
                snapshotAllowance,
                versionOverrides);
        long traversalStarted = System.nanoTime();
        ResolutionGraph graph = traverser.traverse(requests);
        metrics.addGraphTraversalNanos(elapsedSince(traversalStarted));
        long selectionStarted = System.nanoTime();
        VersionSelectionResult selection = versionSelector.select(requests, graph);
        metrics.addVersionSelectionNanos(elapsedSince(selectionStarted));
        return new DependencyGraphResolution(graph, selection);
    }

    private static long elapsedSince(long started) {
        return System.nanoTime() - started;
    }
}
