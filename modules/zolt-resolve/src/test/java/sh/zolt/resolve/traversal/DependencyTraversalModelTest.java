package sh.zolt.resolve.traversal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.maven.repository.RawPomDependency;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.request.DependencyExclusion;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DependencyTraversalModelTest {
    @Test
    void nodeKeysSortByPackageThenVersionAndCanBeBuiltFromNodes() {
        DependencyTraversalNodeKey oldCore = key("com.example", "core", "1.0.0");
        DependencyTraversalNodeKey newCore = key("com.example", "core", "2.0.0");
        DependencyTraversalNodeKey api = key("com.example", "api", "1.0.0");

        List<DependencyTraversalNodeKey> keys = new ArrayList<>(List.of(oldCore, newCore, api));
        keys.sort(DependencyTraversalNodeKey::compareTo);

        assertEquals(List.of(api, oldCore, newCore), keys);
        assertEquals(
                oldCore,
                DependencyTraversalNodeKey.from(new PackageNode(new PackageId("com.example", "core"), "1.0.0")));
    }

    @Test
    void candidateRequiresItemSourceAndDependency() {
        DependencyTraversalItem item = DependencyTraversalItem.direct(request());
        PackageNode source = new PackageNode(new PackageId("com.example", "root"), "1.0.0");
        NormalizedDependency dependency = dependency();

        assertFailure(
                () -> new DependencyTraversalCandidate(null, source, dependency),
                "Dependency traversal item is required.");
        assertFailure(
                () -> new DependencyTraversalCandidate(item, null, dependency),
                "Dependency traversal source node is required.");
        assertFailure(
                () -> new DependencyTraversalCandidate(item, source, null),
                "Dependency traversal candidate is required.");
    }

    @Test
    void traversalItemsAndNormalizedDependenciesCopyExclusions() {
        DependencyExclusion exclusion = new DependencyExclusion("com.example", "ignored");
        List<DependencyExclusion> exclusions = new ArrayList<>(List.of(exclusion));

        DependencyTraversalItem item = DependencyTraversalItem.transitive(
                new PackageNode(new PackageId("com.example", "root"), "1.0.0"),
                request(),
                exclusions,
                DependencyTraversalDecision.include("kept"));
        NormalizedDependency dependency = new NormalizedDependency(
                new RawPomDependency(
                        "com.example",
                        "library",
                        Optional.of("1.0.0"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        false,
                        List.of()),
                DependencyScope.COMPILE,
                false,
                exclusions);
        exclusions.add(new DependencyExclusion("com.example", "late"));

        assertEquals(List.of(exclusion), item.activeExclusions());
        assertEquals(List.of(exclusion), dependency.exclusions());
        assertThrows(UnsupportedOperationException.class, () ->
                item.activeExclusions().add(new DependencyExclusion("com.example", "extra")));
        assertThrows(UnsupportedOperationException.class, () ->
                dependency.exclusions().add(new DependencyExclusion("com.example", "extra")));
        assertTrue(dependency.excludes(new CoordinateParser().parse("com.example:ignored:1.0.0")));
    }

    private static DependencyTraversalNodeKey key(String groupId, String artifactId, String version) {
        return new DependencyTraversalNodeKey(new PackageId(groupId, artifactId), version);
    }

    private static DependencyRequest request() {
        return new DependencyRequest(
                new PackageId("com.example", "root"),
                "1.0.0",
                DependencyScope.COMPILE,
                RequestOrigin.DIRECT);
    }

    private static NormalizedDependency dependency() {
        return new NormalizedDependency(
                new RawPomDependency(
                        "com.example",
                        "library",
                        Optional.of("1.0.0"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        false,
                        List.of()),
                DependencyScope.COMPILE,
                false,
                List.of());
    }

    private static void assertFailure(Runnable action, String message) {
        GraphTraversalException exception = assertThrows(GraphTraversalException.class, action::run);

        assertEquals(message, exception.getMessage());
    }
}
