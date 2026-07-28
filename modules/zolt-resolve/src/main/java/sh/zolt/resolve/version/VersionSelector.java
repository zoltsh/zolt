package sh.zolt.resolve.version;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.PackageId;
import sh.zolt.dependency.VersionComparator;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestVersionOrigin;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.graph.ResolutionEdge;
import sh.zolt.resolve.graph.ResolutionGraph;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VersionSelector {
    private static final String JUNIT_PLATFORM_GROUP = "org.junit.platform";

    /**
     * Platform artifacts that jupiter 5.12+ also requests, so newest-wins can bump them to a newer
     * platform line than the {@code 1.11.4} console/launcher Zolt injects. These define the line the
     * injected artifacts must be aligned to.
     */
    private static final Set<String> JUNIT_PLATFORM_ANCHOR_ARTIFACTS = Set.of(
            "junit-platform-engine",
            "junit-platform-commons");

    /**
     * Platform artifacts that are dragged in only by the injected {@code junit-platform-console} and so
     * are never bumped by newest-wins. They must be aligned onto the anchor line to avoid a
     * launcher-vs-engine skew that crashes the JUnit worker at discovery.
     */
    private static final Set<String> JUNIT_PLATFORM_INJECTED_ARTIFACTS = Set.of(
            "junit-platform-console",
            "junit-platform-launcher",
            "junit-platform-reporting");

    private final VersionComparator versionComparator;

    public VersionSelector() {
        this(new VersionComparator());
    }

    VersionSelector(VersionComparator versionComparator) {
        this.versionComparator = versionComparator;
    }

    public VersionSelectionResult select(List<DependencyRequest> directRequests, ResolutionGraph graph) {
        Map<PackageVariantKey, List<DependencyRequest>> requestsByPackage = new LinkedHashMap<>();
        List<DependencyRequest> requests = new ArrayList<>();
        requests.addAll(directRequests);
        requests.addAll(graph.edges().stream().map(ResolutionEdge::request).toList());
        requests.stream()
                .sorted(Comparator.comparing(VersionSelector::requestSortKey))
                .forEach(request -> requestsByPackage
                        .computeIfAbsent(PackageVariantKey.of(request), ignored -> new ArrayList<>())
                        .add(request));

        List<PackageNode> selectedNodes = new ArrayList<>();
        List<VersionConflict> conflicts = new ArrayList<>();
        for (Map.Entry<PackageVariantKey, List<DependencyRequest>> entry : requestsByPackage.entrySet()) {
            PackageVariantKey key = entry.getKey();
            List<DependencyRequest> packageRequests = entry.getValue();
            Selection selection = selectVersion(packageRequests);
            selectedNodes.add(new PackageNode(key.packageId(), selection.version(), key.variant()));
            if (distinctVersionCount(packageRequests) > 1) {
                conflicts.add(new VersionConflict(
                        key.packageId(),
                        key.variant(),
                        packageRequests,
                        selection.version(),
                        selection.reason()));
            }
        }

        alignJunitPlatform(selectedNodes, requestsByPackage);

        return new VersionSelectionResult(selectedNodes, conflicts);
    }

    /**
     * Realign the injected junit-platform console/launcher/reporting artifacts onto the same platform
     * line the resolver selected for junit-platform-engine/commons. Without this, a jupiter-5.12 project
     * with no JUnit BOM gets engine/commons bumped to the newer line while console/launcher/reporting stay
     * pinned at the injected {@code 1.11.4} baseline, producing a lockfile whose launcher and engine jars
     * are unaligned and crash the JUnit worker at discovery. Direct pins and platform-managed versions
     * are left untouched; where they leave the platform unreconcilable, resolve hard-fails with an
     * actionable message rather than emitting a crashing lockfile.
     */
    private void alignJunitPlatform(
            List<PackageNode> selectedNodes,
            Map<PackageVariantKey, List<DependencyRequest>> requestsByPackage) {
        String anchorVersion = null;
        for (PackageNode node : selectedNodes) {
            if (node.variant().isDefault()
                    && isPlatformArtifact(node.packageId(), JUNIT_PLATFORM_ANCHOR_ARTIFACTS)
                    && (anchorVersion == null
                            || versionComparator.compare(node.selectedVersion(), anchorVersion) > 0)) {
                anchorVersion = node.selectedVersion();
            }
        }
        if (anchorVersion == null) {
            return;
        }
        for (int index = 0; index < selectedNodes.size(); index++) {
            PackageNode node = selectedNodes.get(index);
            if (!node.variant().isDefault()
                    || !isPlatformArtifact(node.packageId(), JUNIT_PLATFORM_INJECTED_ARTIFACTS)
                    || node.selectedVersion().equals(anchorVersion)) {
                continue;
            }
            if (isPinnedByProject(requestsByPackage.get(PackageVariantKey.of(node)))) {
                throw ResolveException.actionable(
                        "Unaligned JUnit Platform: `"
                                + node.packageId()
                                + "` is pinned at `"
                                + node.selectedVersion()
                                + "` but the resolved junit-platform-engine/commons line is `"
                                + anchorVersion
                                + "`; a launcher-vs-engine skew crashes the JUnit worker at discovery.",
                        "Align `"
                                + node.packageId()
                                + "` to `"
                                + anchorVersion
                                + "` (or add a JUnit BOM / junit-platform-console-standalone that matches the "
                                + "resolved platform line), then run `zolt resolve` again.");
            }
            selectedNodes.set(index, new PackageNode(node.packageId(), anchorVersion, node.variant()));
        }
    }

    private static boolean isPlatformArtifact(PackageId packageId, Set<String> artifacts) {
        return packageId.groupId().equals(JUNIT_PLATFORM_GROUP)
                && artifacts.contains(packageId.artifactId());
    }

    private static boolean isPinnedByProject(List<DependencyRequest> requests) {
        return requests != null && requests.stream().anyMatch(request ->
                request.direct() || request.versionOrigin() == RequestVersionOrigin.MANAGED);
    }

    private Selection selectVersion(List<DependencyRequest> requests) {
        List<DependencyRequest> directRequests = requests.stream()
                .filter(DependencyRequest::direct)
                .toList();
        if (!directRequests.isEmpty()) {
            return new Selection(newestVersion(directRequests), ConflictSelectionReason.DIRECT_DEPENDENCY);
        }
        return new Selection(newestVersion(requests), ConflictSelectionReason.NEWEST_VERSION);
    }

    private String newestVersion(List<DependencyRequest> requests) {
        return requests.stream()
                .map(DependencyRequest::requestedVersion)
                .max(versionComparator)
                .orElseThrow();
    }

    private static long distinctVersionCount(List<DependencyRequest> requests) {
        return requests.stream().map(DependencyRequest::requestedVersion).distinct().count();
    }

    private static String requestSortKey(DependencyRequest request) {
        int originRank = request.direct() ? 0 : 1;
        return request.packageId()
                + ":"
                + request.artifactVariant().key()
                + ":"
                + originRank
                + ":"
                + request.requestedVersion()
                + ":"
                + request.scope();
    }

    private record Selection(String version, ConflictSelectionReason reason) {
    }

    private record PackageVariantKey(PackageId packageId, LockArtifactVariant variant) {
        static PackageVariantKey of(DependencyRequest request) {
            return new PackageVariantKey(request.packageId(), request.artifactVariant());
        }

        static PackageVariantKey of(PackageNode node) {
            return new PackageVariantKey(node.packageId(), node.variant());
        }
    }
}
