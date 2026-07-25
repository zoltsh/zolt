package sh.zolt.resolve.traversal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.PackageId;
import sh.zolt.maven.repository.RawPomDependency;
import sh.zolt.resolve.graph.ResolutionGraph;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DependencyGraphExclusionTraversalTest extends DependencyGraphTraverserTestSupport {
    @Test
    void carriesDirectExclusionThroughThreeLevels() {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:root:1.0.0", pom(
                "com.example",
                "root",
                "1.0.0",
                List.of(dependency("com.example", "a", "1.0.0"))));
        source.put("com.example:a:1.0.0", pom(
                "com.example",
                "a",
                "1.0.0",
                List.of(dependency("com.example", "b", "1.0.0"))));
        source.put("com.example:b:1.0.0", pom(
                "com.example",
                "b",
                "1.0.0",
                List.of(dependency("com.example", "excluded", "1.0.0"))));

        ResolutionGraph graph = traverser(source).traverse(List.of(
                directWithExclusion(
                        "com.example",
                        "root",
                        "1.0.0",
                        "com.example",
                        "excluded")));

        assertFalse(contains(graph, "excluded"));
        assertEquals(List.of(
                "com.example:root:1.0.0->com.example:a:1.0.0",
                "com.example:a:1.0.0->com.example:b:1.0.0"), edgeStrings(graph));
    }

    @Test
    void expandsSameIntermediateForExcludedAndAllowedPaths() {
        assertPathSpecificExclusion(false);
    }

    @Test
    void pathSpecificExclusionIsIndependentOfDeclarationOrder() {
        assertPathSpecificExclusion(true);
    }

    @Test
    void carriesWildcardExclusionBeyondOneLevel() {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:root:1.0.0", pom(
                "com.example",
                "root",
                "1.0.0",
                List.of(dependencyWithExclusion(
                        "com.example",
                        "a",
                        "1.0.0",
                        "com.blocked",
                        "*"))));
        source.put("com.example:a:1.0.0", pom(
                "com.example",
                "a",
                "1.0.0",
                List.of(dependency("com.example", "b", "1.0.0"))));
        source.put("com.example:b:1.0.0", pom(
                "com.example",
                "b",
                "1.0.0",
                List.of(dependency("com.blocked", "deep", "1.0.0"))));

        ResolutionGraph graph = traverser(source).traverse(
                List.of(direct("com.example", "root", "1.0.0")));

        assertFalse(contains(graph, "deep"));
        assertTrue(graph.policyEffects().stream()
                .anyMatch(effect -> effect.packageId().equals(
                        new PackageId("com.blocked", "deep"))));
    }

    private void assertPathSpecificExclusion(boolean reversed) {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        RawPomDependency left = dependencyWithExclusion(
                "com.example",
                "left",
                "1.0.0",
                "com.example",
                "allowed-child");
        RawPomDependency right = dependency("com.example", "right", "1.0.0");
        source.put("com.example:root:1.0.0", pom(
                "com.example",
                "root",
                "1.0.0",
                reversed ? List.of(right, left) : List.of(left, right)));
        source.put("com.example:left:1.0.0", pom(
                "com.example",
                "left",
                "1.0.0",
                List.of(dependency("com.example", "shared", "1.0.0"))));
        source.put("com.example:right:1.0.0", pom(
                "com.example",
                "right",
                "1.0.0",
                List.of(dependency("com.example", "shared", "1.0.0"))));
        source.put("com.example:shared:1.0.0", pom(
                "com.example",
                "shared",
                "1.0.0",
                List.of(dependency("com.example", "allowed-child", "1.0.0"))));
        source.put("com.example:allowed-child:1.0.0", pom(
                "com.example",
                "allowed-child",
                "1.0.0",
                List.of()));

        ResolutionGraph graph = traverser(source).traverse(
                List.of(direct("com.example", "root", "1.0.0")));

        assertTrue(contains(graph, "allowed-child"));
        assertEquals(1, graph.edges().stream()
                .filter(edge -> edge.from().packageId().artifactId().equals("shared"))
                .filter(edge -> edge.to().packageId().artifactId().equals("allowed-child"))
                .count());
    }

    private static boolean contains(ResolutionGraph graph, String artifactId) {
        return graph.nodes().stream()
                .anyMatch(node -> node.packageId().artifactId().equals(artifactId));
    }
}
