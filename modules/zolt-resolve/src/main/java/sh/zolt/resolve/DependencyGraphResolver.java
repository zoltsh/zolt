package sh.zolt.resolve;

import sh.zolt.dependency.PackageId;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.metrics.ResolverMetricsSink;
import sh.zolt.resolve.metadata.DependencyMetadataSource;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.traversal.DependencyGraphTraverser;
import sh.zolt.resolve.version.VersionConflict;
import sh.zolt.resolve.version.VersionSelectionResult;
import sh.zolt.resolve.version.VersionSelector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DependencyGraphResolver {
    private static final int MAX_MATERIALIZATION_PASSES = 100;
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
        Map<ResolutionVariant, String> materializedVersions = new LinkedHashMap<>(versionOverrides);
        List<DependencyRequest> selectionRequests =
                DependencyRequestVersions.rewrite(requests, versionOverrides);
        Map<ResolutionVariant, VersionConflict> preservedConflicts = new LinkedHashMap<>();
        Set<Map<ResolutionVariant, String>> seenSelections = new LinkedHashSet<>();
        seenSelections.add(Map.copyOf(materializedVersions));

        for (int pass = 1; pass <= MAX_MATERIALIZATION_PASSES; pass++) {
            List<DependencyRequest> traversalRequests =
                    DependencyRequestVersions.rewrite(selectionRequests, materializedVersions);
            DependencyGraphTraverser traverser = graphTraverserFactory.create(
                    metadataSource,
                    dependencyPolicy,
                    managedVersions,
                    retryCommand,
                    snapshotAllowance,
                    materializedVersions,
                    versionOverrides);
            long traversalStarted = System.nanoTime();
            ResolutionGraph graph = traverser.traverse(traversalRequests);
            metrics.addGraphTraversalNanos(elapsedSince(traversalStarted));

            long selectionStarted = System.nanoTime();
            VersionSelectionResult selection = versionSelector.select(selectionRequests, graph);
            metrics.addVersionSelectionNanos(elapsedSince(selectionStarted));
            preserveConflicts(preservedConflicts, selection.conflicts());

            Map<ResolutionVariant, String> selectedVersions = selectedVersions(selection);
            selectedVersions.putAll(versionOverrides);
            if (selectedVersions.equals(materializedVersions)) {
                return new DependencyGraphResolution(
                        graph,
                        new VersionSelectionResult(
                                selection.selectedNodes(),
                                List.copyOf(preservedConflicts.values())));
            }
            Map<ResolutionVariant, String> nextSelection = Map.copyOf(selectedVersions);
            if (!seenSelections.add(nextSelection)) {
                throw ResolveException.actionable(
                        "Dependency version selection did not stabilize while materializing the selected graph.",
                        "Align the cycling versions with a direct dependency, [platforms] BOM, or "
                                + "[dependencyConstraints] strict constraint, then run `"
                                + retryCommand
                                + "` again.");
            }
            materializedVersions = new LinkedHashMap<>(nextSelection);
        }
        throw ResolveException.actionable(
                "Dependency version selection did not stabilize after "
                        + MAX_MATERIALIZATION_PASSES
                        + " materialization passes.",
                "Align the changing versions with a direct dependency, [platforms] BOM, or "
                        + "[dependencyConstraints] strict constraint, then run `"
                        + retryCommand
                        + "` again.");
    }

    private static Map<ResolutionVariant, String> selectedVersions(VersionSelectionResult selection) {
        Map<ResolutionVariant, String> selectedVersions = new LinkedHashMap<>();
        selection.selectedNodes().forEach(node -> selectedVersions.put(
                new ResolutionVariant(node.packageId(), node.variant()),
                node.selectedVersion()));
        return selectedVersions;
    }

    private static void preserveConflicts(
            Map<ResolutionVariant, VersionConflict> preserved,
            List<VersionConflict> conflicts) {
        for (VersionConflict conflict : conflicts) {
            ResolutionVariant key = new ResolutionVariant(
                    conflict.packageId(), conflict.variant());
            VersionConflict previous = preserved.get(key);
            if (previous == null) {
                preserved.put(key, conflict);
                continue;
            }
            List<DependencyRequest> requests = new ArrayList<>(previous.requests());
            conflict.requests().stream()
                    .filter(request -> !requests.contains(request))
                    .forEach(requests::add);
            preserved.put(key, new VersionConflict(
                    conflict.packageId(),
                    conflict.variant(),
                    requests,
                    conflict.selectedVersion(),
                    conflict.selectionReason()));
        }
    }

    private static long elapsedSince(long started) {
        return System.nanoTime() - started;
    }
}
