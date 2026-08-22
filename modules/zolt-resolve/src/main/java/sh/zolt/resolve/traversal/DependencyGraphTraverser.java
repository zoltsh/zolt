package sh.zolt.resolve.traversal;

import sh.zolt.dependency.PackageId;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.EffectiveRawPom;
import sh.zolt.maven.repository.PomDependencyManager;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.resolve.request.DependencyExclusion;
import sh.zolt.resolve.DependencyPolicyEffect;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolutionVariant;
import sh.zolt.resolve.SnapshotAllowance;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.graph.ResolutionEdge;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.graph.ResolutionReachability;
import sh.zolt.resolve.metadata.DependencyMetadataSource;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeSet;

public final class DependencyGraphTraverser {
    private final DependencyMetadataSource metadataSource;
    private final PomDependencyManager dependencyManager;
    private final DependencyNormalizer normalizer;
    private final DependencyTraversalCandidateSelector candidateSelector;
    private final DependencyRelocator relocator;
    private final Map<ResolutionVariant, String> materializedVersions;

    public DependencyGraphTraverser(DependencyMetadataSource metadataSource) {
        this(metadataSource, DependencyPolicySettings.defaults(), Map.of());
    }

    public DependencyGraphTraverser(
            DependencyMetadataSource metadataSource,
            DependencyPolicySettings dependencyPolicy) {
        this(metadataSource, dependencyPolicy, Map.of());
    }

    public DependencyGraphTraverser(
            DependencyMetadataSource metadataSource,
            DependencyPolicySettings dependencyPolicy,
            Map<PackageId, ManagedVersion> rootManagedVersions) {
        this(metadataSource, dependencyPolicy, rootManagedVersions, "zolt resolve");
    }

    public DependencyGraphTraverser(
            DependencyMetadataSource metadataSource,
            DependencyPolicySettings dependencyPolicy,
            Map<PackageId, ManagedVersion> rootManagedVersions,
            String retryCommand) {
        this(metadataSource, dependencyPolicy, rootManagedVersions, retryCommand, SnapshotAllowance.none());
    }

    public DependencyGraphTraverser(
            DependencyMetadataSource metadataSource,
            DependencyPolicySettings dependencyPolicy,
            Map<PackageId, ManagedVersion> rootManagedVersions,
            String retryCommand,
            SnapshotAllowance snapshotAllowance) {
        this(
                metadataSource,
                new PomDependencyManager(),
                new DependencyNormalizer(),
                new DependencyTraversalPolicy(),
                dependencyPolicy,
                rootManagedVersions,
                retryCommand,
                snapshotAllowance,
                Map.of(),
                Map.of());
    }

    public DependencyGraphTraverser(
            DependencyMetadataSource metadataSource,
            DependencyPolicySettings dependencyPolicy,
            Map<PackageId, ManagedVersion> rootManagedVersions,
            String retryCommand,
            SnapshotAllowance snapshotAllowance,
            Map<ResolutionVariant, String> versionOverrides) {
        this(
                metadataSource,
                new PomDependencyManager(),
                new DependencyNormalizer(),
                new DependencyTraversalPolicy(),
                dependencyPolicy,
                rootManagedVersions,
                retryCommand,
                snapshotAllowance,
                versionOverrides,
                versionOverrides);
    }

    public DependencyGraphTraverser(
            DependencyMetadataSource metadataSource,
            DependencyPolicySettings dependencyPolicy,
            Map<PackageId, ManagedVersion> rootManagedVersions,
            String retryCommand,
            SnapshotAllowance snapshotAllowance,
            Map<ResolutionVariant, String> versionOverrides,
            Map<ResolutionVariant, String> workspaceVersionOverrides) {
        this(
                metadataSource,
                new PomDependencyManager(),
                new DependencyNormalizer(),
                new DependencyTraversalPolicy(),
                dependencyPolicy,
                rootManagedVersions,
                retryCommand,
                snapshotAllowance,
                versionOverrides,
                workspaceVersionOverrides);
    }

    DependencyGraphTraverser(
            DependencyMetadataSource metadataSource,
            PomDependencyManager dependencyManager,
            DependencyNormalizer normalizer,
            DependencyTraversalPolicy traversalPolicy,
            DependencyPolicySettings dependencyPolicy,
            Map<PackageId, ManagedVersion> rootManagedVersions,
            String retryCommand,
            SnapshotAllowance snapshotAllowance,
            Map<ResolutionVariant, String> versionOverrides,
            Map<ResolutionVariant, String> workspaceVersionOverrides) {
        this.metadataSource = metadataSource;
        this.dependencyManager = dependencyManager;
        this.normalizer = normalizer;
        this.relocator = new DependencyRelocator(metadataSource);
        this.materializedVersions =
                versionOverrides == null ? Map.of() : Map.copyOf(versionOverrides);
        this.candidateSelector = new DependencyTraversalCandidateSelector(
                traversalPolicy,
                new DependencyTransitiveScopeSelector(),
                globalExclusions(dependencyPolicy, retryCommand),
                strictConstraints(dependencyPolicy),
                rootManagedVersions,
                retryCommand,
                snapshotAllowance,
                versionOverrides,
                workspaceVersionOverrides);
    }

    public ResolutionGraph traverse(List<DependencyRequest> directRequests) {
        SequencedMap<DependencyTraversalNodeKey, PackageNode> nodes = new LinkedHashMap<>();
        List<ResolutionEdge> edges = new ArrayList<>();
        List<ResolutionReachability> reachability = new ArrayList<>();
        List<DependencyPolicyEffect> policyEffects = new ArrayList<>();
        Set<DependencyTraversalVisitKey> visited = new TreeSet<>();
        ArrayDeque<DependencyTraversalItem> queue = new ArrayDeque<>();

        directRequests.stream()
                .sorted(Comparator.comparing(DependencyTraversalOrdering::requestSortKey))
                .forEach(request -> queue.add(DependencyTraversalItem.direct(
                        request,
                        materializedVersion(request))));

        while (!queue.isEmpty()) {
            List<DependencyTraversalItem> frontier = frontier(queue);
            metadataSource.preload(frontier.stream()
                    .map(this::materializedRequest)
                    .map(DependencyGraphTraverser::coordinate)
                    .sorted(Comparator.comparing(Coordinate::toString))
                    .toList());
            for (DependencyTraversalItem item : frontier) {
                DependencyRequest materializedRequest = materializedRequest(item);
                DependencyRelocator.RelocationResult relocated =
                        relocator.relocateWithPom(materializedRequest);
                DependencyRequest resolvedRequest = relocated.request();
                String version = requireVersion(resolvedRequest);
                PackageNode node = node(resolvedRequest, version);
                DependencyTraversalNodeKey nodeKey = DependencyTraversalNodeKey.from(node);
                nodes.putIfAbsent(nodeKey, node);
                reachability.add(new ResolutionReachability(
                        node,
                        resolvedRequest.scope(),
                        item.optionalRoot()));

                item.parent().ifPresent(parent -> edges.add(new ResolutionEdge(
                        parent,
                        node,
                        selectionRequest(item.request(), materializedRequest, resolvedRequest),
                        item.sourceScope(),
                        item.decision())));

                if (!visited.add(DependencyTraversalVisitKey.from(
                        node,
                        resolvedRequest.scope(),
                        item.activeExclusions(),
                        item.optionalRoot()))) {
                    continue;
                }

                EffectiveRawPom pom = relocated.pom();
                List<NormalizedDependency> dependencies = dependencyManager.applyManagedVersions(pom).stream()
                        .map(normalizer::normalize)
                        .sorted(Comparator.comparing(DependencyTraversalOrdering::dependencySortKey))
                        .toList();

                for (NormalizedDependency dependency : dependencies) {
                    DependencyTraversalSelection selection = candidateSelector.select(
                            new DependencyTraversalCandidate(item, node, dependency));
                    policyEffects.addAll(selection.policyEffects());
                    selection.selectedItem().ifPresent(queue::add);
                }
            }
        }

        return new ResolutionGraph(
                List.copyOf(nodes.values()),
                edges.stream().distinct().toList(),
                List.of(),
                DependencyTraversalOrdering.sortedPolicyEffects(policyEffects),
                reachability.stream().distinct().toList());
    }

    private static List<DependencyTraversalItem> frontier(ArrayDeque<DependencyTraversalItem> queue) {
        int size = queue.size();
        List<DependencyTraversalItem> frontier = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            frontier.add(queue.removeFirst());
        }
        return frontier;
    }

    private static PackageNode node(DependencyRequest request, String version) {
        return new PackageNode(request.packageId(), version, request.artifactVariant());
    }

    private String materializedVersion(DependencyRequest request) {
        return materializedVersions.getOrDefault(
                new ResolutionVariant(request.packageId(), request.artifactVariant()),
                request.requestedVersion());
    }

    private DependencyRequest materializedRequest(DependencyTraversalItem item) {
        return requestWithVersion(item.request(), item.materializedVersion());
    }

    private static DependencyRequest selectionRequest(
            DependencyRequest requested,
            DependencyRequest materialized,
            DependencyRequest resolved) {
        if (resolved.packageId().equals(materialized.packageId())
                && resolved.requestedVersion().equals(materialized.requestedVersion())) {
            return requested;
        }
        return resolved;
    }

    private static DependencyRequest requestWithVersion(
            DependencyRequest request,
            String version) {
        if (version.equals(request.requestedVersion())) {
            return request;
        }
        Optional<sh.zolt.maven.ArtifactDescriptor> descriptor = request.artifactDescriptor()
                .map(value -> new sh.zolt.maven.ArtifactDescriptor(
                        new Coordinate(
                                request.packageId().groupId(),
                                request.packageId().artifactId(),
                                Optional.of(version)),
                        value.classifier(),
                        value.extension()));
        return new DependencyRequest(
                request.packageId(),
                version,
                request.scope(),
                request.origin(),
                descriptor,
                request.exclusions(),
                request.optional(),
                request.versionOrigin());
    }

    private static String requireVersion(DependencyRequest request) {
        if (request.requestedVersion() == null || request.requestedVersion().isBlank()) {
            throw new GraphTraversalException(
                    "Dependency request for " + request.packageId() + " must include a version.");
        }
        return request.requestedVersion();
    }

    private static Coordinate coordinate(DependencyRequest request) {
        return new Coordinate(
                request.packageId().groupId(),
                request.packageId().artifactId(),
                Optional.ofNullable(request.requestedVersion()));
    }

    private static List<DependencyGlobalExclusion> globalExclusions(
            DependencyPolicySettings dependencyPolicy,
            String retryCommand) {
        if (dependencyPolicy == null) {
            return List.of();
        }
        return dependencyPolicy.exclusions().stream()
                .map(exclusion -> {
                    if ("*".equals(exclusion.group()) || "*".equals(exclusion.artifact())) {
                        throw ResolveException.actionable(
                                "Wildcard dependency exclusions are not supported in [dependencies.policy].deny: "
                                        + exclusion.group()
                                        + ":"
                                        + exclusion.artifact()
                                        + ".",
                                "Replace it with explicit group and artifact exclusions, then run `"
                                        + retryCommand
                                        + "` again.");
                    }
                    return new DependencyGlobalExclusion(
                            new DependencyExclusion(exclusion.group(), exclusion.artifact()),
                            exclusion.reason());
                })
                .toList();
    }

    private static Map<PackageId, DependencyConstraint> strictConstraints(DependencyPolicySettings dependencyPolicy) {
        if (dependencyPolicy == null) {
            return Map.of();
        }
        Map<PackageId, DependencyConstraint> constraints = new LinkedHashMap<>();
        for (DependencyConstraint constraint : dependencyPolicy.constraints().values()) {
            String[] parts = constraint.coordinate().split(":", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new GraphTraversalException(
                        "Invalid dependency constraint coordinate `"
                                + constraint.coordinate()
                                + "`. Use `group:artifact`.");
            }
            constraints.put(new PackageId(parts[0], parts[1]), constraint);
        }
        return Map.copyOf(constraints);
    }

}
