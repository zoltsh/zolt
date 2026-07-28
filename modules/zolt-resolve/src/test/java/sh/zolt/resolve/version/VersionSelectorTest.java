package sh.zolt.resolve.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.request.RequestOrigin;
import sh.zolt.resolve.request.RequestVersionOrigin;
import sh.zolt.resolve.graph.ResolutionEdge;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.traversal.DependencyTraversalDecision;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class VersionSelectorTest {
    private final VersionSelector selector = new VersionSelector();

    @Test
    void directDependencyWinsOverTransitiveDependency() {
        PackageId packageId = new PackageId("org.slf4j", "slf4j-api");
        ResolutionGraph graph = graphWithTransitiveRequests(List.of(
                transitive(packageId, "2.0.16")));

        VersionSelectionResult result = selector.select(List.of(direct(packageId, "2.0.15")), graph);

        assertEquals(List.of(new PackageNode(packageId, "2.0.15")), result.selectedNodes());
        VersionConflict conflict = result.conflicts().getFirst();
        assertEquals("2.0.15", conflict.selectedVersion());
        assertEquals(ConflictSelectionReason.DIRECT_DEPENDENCY, conflict.selectionReason());
    }

    @Test
    void otherwiseNewestVersionWins() {
        PackageId packageId = new PackageId("org.slf4j", "slf4j-api");
        ResolutionGraph graph = graphWithTransitiveRequests(List.of(
                transitive(packageId, "2.0.15"),
                transitive(packageId, "2.0.16")));

        VersionSelectionResult result = selector.select(List.of(), graph);

        assertEquals(List.of(new PackageNode(packageId, "2.0.16")), result.selectedNodes());
        VersionConflict conflict = result.conflicts().getFirst();
        assertEquals("2.0.16", conflict.selectedVersion());
        assertEquals(ConflictSelectionReason.NEWEST_VERSION, conflict.selectionReason());
    }

    @Test
    void conflictsAreRecordedNotHidden() {
        PackageId packageId = new PackageId("com.google.guava", "guava");
        ResolutionGraph graph = graphWithTransitiveRequests(List.of(
                transitive(packageId, "33.3.1-jre"),
                transitive(packageId, "33.4.0-jre")));

        VersionSelectionResult result = selector.select(List.of(), graph);

        assertEquals(1, result.conflicts().size());
        VersionConflict conflict = result.conflicts().getFirst();
        assertEquals(packageId, conflict.packageId());
        assertEquals(List.of("33.3.1-jre", "33.4.0-jre"), conflict.requests().stream().map(DependencyRequest::requestedVersion).toList());
    }

    @Test
    void noConflictWhenAllRequestsUseSameVersion() {
        PackageId packageId = new PackageId("org.slf4j", "slf4j-api");
        ResolutionGraph graph = graphWithTransitiveRequests(List.of(
                transitive(packageId, "2.0.16"),
                transitive(packageId, "2.0.16")));

        VersionSelectionResult result = selector.select(List.of(), graph);

        assertEquals(List.of(new PackageNode(packageId, "2.0.16")), result.selectedNodes());
        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    void differentArtifactVariantsMediateInIndependentLanes() {
        PackageId packageId = new PackageId("com.example", "fixture");
        DependencyRequest linux = classified(
                packageId, "1.0.0", "linux-x86_64", DependencyScope.COMPILE, RequestOrigin.DIRECT);
        DependencyRequest tests = classified(
                packageId, "2.0.0", "tests", DependencyScope.TEST, RequestOrigin.DIRECT);

        VersionSelectionResult result = selector.select(List.of(linux, tests), new ResolutionGraph(
                List.of(), List.of(), List.of()));

        assertEquals(
                List.of("1.0.0:jar|linux-x86_64", "2.0.0:jar|tests"),
                result.selectedNodes().stream()
                        .map(node -> node.selectedVersion() + ":" + node.variant().key())
                        .toList());
        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    void sameInputProducesSameSelectionRegardlessOfOrder() {
        PackageId slf4j = new PackageId("org.slf4j", "slf4j-api");
        PackageId guava = new PackageId("com.google.guava", "guava");
        ResolutionGraph leftGraph = graphWithTransitiveRequests(List.of(
                transitive(slf4j, "2.0.15"),
                transitive(guava, "33.4.0-jre"),
                transitive(slf4j, "2.0.16")));
        ResolutionGraph rightGraph = graphWithTransitiveRequests(List.of(
                transitive(slf4j, "2.0.16"),
                transitive(slf4j, "2.0.15"),
                transitive(guava, "33.4.0-jre")));

        VersionSelectionResult left = selector.select(List.of(), leftGraph);
        VersionSelectionResult right = selector.select(List.of(), rightGraph);

        assertEquals(left.selectedNodes(), right.selectedNodes());
        assertEquals(left.conflicts(), right.conflicts());
    }

    @Test
    void selectedNodesAreSortedDeterministically() {
        PackageId slf4j = new PackageId("org.slf4j", "slf4j-api");
        PackageId guava = new PackageId("com.google.guava", "guava");
        ResolutionGraph graph = graphWithTransitiveRequests(List.of(
                transitive(slf4j, "2.0.16"),
                transitive(guava, "33.4.0-jre")));

        VersionSelectionResult result = selector.select(List.of(), graph);

        assertEquals(List.of(
                new PackageNode(guava, "33.4.0-jre"),
                new PackageNode(slf4j, "2.0.16")), result.selectedNodes());
    }

    @Test
    void injectedConsoleLauncherReportingAlignToResolvedPlatformLine() {
        PackageId engine = new PackageId("org.junit.platform", "junit-platform-engine");
        PackageId commons = new PackageId("org.junit.platform", "junit-platform-commons");
        PackageId console = new PackageId("org.junit.platform", "junit-platform-console");
        PackageId launcher = new PackageId("org.junit.platform", "junit-platform-launcher");
        PackageId reporting = new PackageId("org.junit.platform", "junit-platform-reporting");
        ResolutionGraph graph = graphWithTransitiveRequests(List.of(
                transitive(engine, "1.12.0"),
                transitive(commons, "1.12.0"),
                transitive(console, "1.11.4"),
                transitive(launcher, "1.11.4"),
                transitive(reporting, "1.11.4")));

        VersionSelectionResult result = selector.select(List.of(), graph);

        assertEquals("1.12.0", selectedVersion(result, console));
        assertEquals("1.12.0", selectedVersion(result, launcher));
        assertEquals("1.12.0", selectedVersion(result, reporting));
        assertEquals("1.12.0", selectedVersion(result, engine));
    }

    @Test
    void alreadyAlignedPlatformIsUnchanged() {
        PackageId engine = new PackageId("org.junit.platform", "junit-platform-engine");
        PackageId launcher = new PackageId("org.junit.platform", "junit-platform-launcher");
        ResolutionGraph graph = graphWithTransitiveRequests(List.of(
                transitive(engine, "1.11.4"),
                transitive(launcher, "1.11.4")));

        VersionSelectionResult result = selector.select(List.of(), graph);

        assertEquals("1.11.4", selectedVersion(result, engine));
        assertEquals("1.11.4", selectedVersion(result, launcher));
        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    void projectManagedConsoleAtSkewedLineHardFailsWithActionableMessage() {
        PackageId engine = new PackageId("org.junit.platform", "junit-platform-engine");
        PackageId console = new PackageId("org.junit.platform", "junit-platform-console");
        ResolutionGraph graph = graphWithTransitiveRequests(List.of(
                transitive(engine, "1.12.0")));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> selector.select(List.of(direct(console, "1.11.4")), graph));

        assertTrue(exception.getMessage().contains("junit-platform-console"));
        assertTrue(exception.getMessage().contains("1.11.4"));
        assertTrue(exception.getMessage().contains("1.12.0"));
    }

    @Test
    void injectedConsoleWithManagedVersionAtSkewedLineHardFails() {
        PackageId engine = new PackageId("org.junit.platform", "junit-platform-engine");
        PackageId console = new PackageId("org.junit.platform", "junit-platform-console");
        ResolutionGraph graph = graphWithTransitiveRequests(List.of(
                transitive(engine, "1.10.2")));
        DependencyRequest managedConsole = new DependencyRequest(
                console,
                "1.11.4",
                DependencyScope.TEST,
                RequestOrigin.TRANSITIVE,
                RequestVersionOrigin.MANAGED);

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> selector.select(List.of(managedConsole), graph));

        assertTrue(exception.getMessage().contains("junit-platform-console"));
        assertTrue(exception.getMessage().contains("1.11.4"));
        assertTrue(exception.getMessage().contains("1.10.2"));
    }

    @Test
    void platformAlignmentIgnoresProjectsWithoutJunitPlatform() {
        PackageId slf4j = new PackageId("org.slf4j", "slf4j-api");
        ResolutionGraph graph = graphWithTransitiveRequests(List.of(
                transitive(slf4j, "2.0.16")));

        VersionSelectionResult result = selector.select(List.of(), graph);

        assertEquals(List.of(new PackageNode(slf4j, "2.0.16")), result.selectedNodes());
    }

    private static String selectedVersion(VersionSelectionResult result, PackageId packageId) {
        return result.selectedNodes().stream()
                .filter(node -> node.packageId().equals(packageId))
                .map(PackageNode::selectedVersion)
                .findFirst()
                .orElseThrow();
    }

    private static ResolutionGraph graphWithTransitiveRequests(List<DependencyRequest> requests) {
        PackageNode root = new PackageNode(new PackageId("com.example", "root"), "1.0.0");
        List<ResolutionEdge> edges = requests.stream()
                .map(request -> new ResolutionEdge(
                        root,
                        new PackageNode(request.packageId(), request.requestedVersion()),
                        request,
                        DependencyTraversalDecision.include("test")))
                .toList();
        return new ResolutionGraph(List.of(), edges, List.of());
    }

    private static DependencyRequest direct(PackageId packageId, String version) {
        return new DependencyRequest(packageId, version, DependencyScope.COMPILE, RequestOrigin.DIRECT);
    }

    private static DependencyRequest transitive(PackageId packageId, String version) {
        return new DependencyRequest(packageId, version, DependencyScope.COMPILE, RequestOrigin.TRANSITIVE);
    }

    private static DependencyRequest classified(
            PackageId packageId,
            String version,
            String classifier,
            DependencyScope scope,
            RequestOrigin origin) {
        return new DependencyRequest(
                packageId,
                version,
                scope,
                origin,
                Optional.of(ArtifactDescriptor.jar(
                        new Coordinate(
                                packageId.groupId(),
                                packageId.artifactId(),
                                Optional.of(version)),
                        Optional.of(classifier))));
    }
}
