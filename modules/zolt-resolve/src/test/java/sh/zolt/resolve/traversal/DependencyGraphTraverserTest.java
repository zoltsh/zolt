package sh.zolt.resolve.traversal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyConstraintKind;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class DependencyGraphTraverserTest extends DependencyGraphTraverserTestSupport {
    @Test
    void retainsOptionalRootReachabilityBeforeExclusionContextsAreFlattened() {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put(
                "com.example:required-root:1.0.0",
                pom(
                        "com.example",
                        "required-root",
                        "1.0.0",
                        List.of(dependencyWithExclusion(
                                "com.example",
                                "shared",
                                "1.0.0",
                                "com.example",
                                "leaf"))));
        source.put(
                "com.example:optional-root:1.0.0",
                pom(
                        "com.example",
                        "optional-root",
                        "1.0.0",
                        List.of(dependency(
                                "com.example",
                                "shared",
                                "1.0.0"))));
        source.put(
                "com.example:shared:1.0.0",
                pom(
                        "com.example",
                        "shared",
                        "1.0.0",
                        List.of(dependency(
                                "com.example",
                                "leaf",
                                "1.0.0"))));
        source.put(
                "com.example:leaf:1.0.0",
                pom("com.example", "leaf", "1.0.0", List.of()));

        ResolutionGraph graph = traverser(source).traverse(List.of(
                optionalDirect(
                        "com.example", "optional-root", "1.0.0"),
                direct(
                        "com.example", "required-root", "1.0.0")));

        Map<String, Set<Boolean>> roots = graph.reachability().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        fact -> fact.node().packageId().artifactId(),
                        java.util.stream.Collectors.mapping(
                                sh.zolt.resolve.graph.ResolutionReachability::optionalRoot,
                                java.util.stream.Collectors.toSet())));
        assertEquals(Set.of(false), roots.get("required-root"));
        assertEquals(Set.of(true), roots.get("optional-root"));
        assertEquals(Set.of(false, true), roots.get("shared"));
        assertEquals(Set.of(true), roots.get("leaf"));
    }

    @Test
    void resolvesGuavaFixtureShape() {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.google.guava:guava:33.4.0-jre", pom("com.google.guava", "guava", "33.4.0-jre", List.of(
                dependency("com.google.guava", "failureaccess", "1.0.2"),
                dependency("com.google.guava", "listenablefuture", "9999.0-empty-to-avoid-conflict-with-guava"),
                dependency("org.checkerframework", "checker-qual", "3.43.0"))));
        source.put("com.google.guava:failureaccess:1.0.2", pom("com.google.guava", "failureaccess", "1.0.2", List.of()));
        source.put("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava", pom("com.google.guava", "listenablefuture", "9999.0-empty-to-avoid-conflict-with-guava", List.of()));
        source.put("org.checkerframework:checker-qual:3.43.0", pom("org.checkerframework", "checker-qual", "3.43.0", List.of()));

        ResolutionGraph graph = traverser(source).traverse(List.of(direct("com.google.guava", "guava", "33.4.0-jre")));

        assertEquals(List.of(
                "com.google.guava:guava:33.4.0-jre",
                "com.google.guava:failureaccess:1.0.2",
                "com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava",
                "org.checkerframework:checker-qual:3.43.0"), nodeStrings(graph));
        assertEquals(3, graph.edges().size());
    }

    @Test
    void resolvesSlf4jFixtureShape() {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("org.slf4j:slf4j-api:2.0.16", pom("org.slf4j", "slf4j-api", "2.0.16", List.of()));

        ResolutionGraph graph = traverser(source).traverse(List.of(direct("org.slf4j", "slf4j-api", "2.0.16")));

        assertEquals(List.of("org.slf4j:slf4j-api:2.0.16"), nodeStrings(graph));
        assertEquals(0, graph.edges().size());
    }

    @Test
    void resolvesJunitFixtureFarEnoughForTestingWork() {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("org.junit.jupiter:junit-jupiter:5.11.4", pom("org.junit.jupiter", "junit-jupiter", "5.11.4", List.of(
                dependency("org.junit.jupiter", "junit-jupiter-api", "5.11.4"),
                dependency("org.junit.jupiter", "junit-jupiter-params", "5.11.4"),
                runtimeDependency("org.junit.jupiter", "junit-jupiter-engine", "5.11.4"))));
        source.put("org.junit.jupiter:junit-jupiter-api:5.11.4", pom("org.junit.jupiter", "junit-jupiter-api", "5.11.4", List.of()));
        source.put("org.junit.jupiter:junit-jupiter-params:5.11.4", pom("org.junit.jupiter", "junit-jupiter-params", "5.11.4", List.of()));
        source.put("org.junit.jupiter:junit-jupiter-engine:5.11.4", pom("org.junit.jupiter", "junit-jupiter-engine", "5.11.4", List.of()));

        ResolutionGraph graph = traverser(source).traverse(List.of(direct("org.junit.jupiter", "junit-jupiter", "5.11.4")));

        assertEquals(List.of(
                "org.junit.jupiter:junit-jupiter:5.11.4",
                "org.junit.jupiter:junit-jupiter-api:5.11.4",
                "org.junit.jupiter:junit-jupiter-engine:5.11.4",
                "org.junit.jupiter:junit-jupiter-params:5.11.4"), nodeStrings(graph));
        assertEquals(DependencyScope.RUNTIME, graph.edges().get(1).request().scope());
    }

    @Test
    void avoidsInfiniteLoopsAndVisitsNodesOnce() {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:a:1.0.0", pom("com.example", "a", "1.0.0", List.of(dependency("com.example", "b", "1.0.0"))));
        source.put("com.example:b:1.0.0", pom("com.example", "b", "1.0.0", List.of(dependency("com.example", "a", "1.0.0"))));

        ResolutionGraph graph = traverser(source).traverse(List.of(direct("com.example", "a", "1.0.0")));

        assertEquals(List.of("com.example:a:1.0.0", "com.example:b:1.0.0"), nodeStrings(graph));
        assertEquals(2, graph.edges().size());
        assertEquals(1, source.loadCount("com.example:a:1.0.0"));
        assertEquals(1, source.loadCount("com.example:b:1.0.0"));
    }

    @Test
    void recordsVisitedNodesDeterministically() {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:root:1.0.0", pom("com.example", "root", "1.0.0", List.of(
                dependency("com.example", "zeta", "1.0.0"),
                dependency("com.example", "alpha", "1.0.0"))));
        source.put("com.example:alpha:1.0.0", pom("com.example", "alpha", "1.0.0", List.of()));
        source.put("com.example:zeta:1.0.0", pom("com.example", "zeta", "1.0.0", List.of()));

        ResolutionGraph graph = traverser(source).traverse(List.of(direct("com.example", "root", "1.0.0")));

        assertEquals(List.of(
                "com.example:root:1.0.0",
                "com.example:alpha:1.0.0",
                "com.example:zeta:1.0.0"), nodeStrings(graph));
    }

    @Test
    void skipsOptionalTransitiveDependencies() {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:root:1.0.0", pom("com.example", "root", "1.0.0", List.of(optionalDependency("com.example", "optional", "1.0.0"))));

        ResolutionGraph graph = traverser(source).traverse(List.of(direct("com.example", "root", "1.0.0")));

        assertEquals(List.of("com.example:root:1.0.0"), nodeStrings(graph));
    }

    @Test
    void directRequestExclusionAppliesOnlyThroughDeclaringEdge() {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:root:1.0.0", pom("com.example", "root", "1.0.0", List.of(
                dependency("com.example", "shared", "1.0.0"),
                dependency("com.example", "excluded", "1.0.0"))));
        source.put("com.example:other:1.0.0", pom("com.example", "other", "1.0.0", List.of(
                dependency("com.example", "excluded", "1.0.0"))));
        source.put("com.example:shared:1.0.0", pom("com.example", "shared", "1.0.0", List.of()));
        source.put("com.example:excluded:1.0.0", pom("com.example", "excluded", "1.0.0", List.of()));

        ResolutionGraph graph = traverser(source).traverse(List.of(
                directWithExclusion("com.example", "root", "1.0.0", "com.example", "excluded"),
                direct("com.example", "other", "1.0.0")));

        assertEquals(List.of(
                "com.example:other:1.0.0",
                "com.example:root:1.0.0",
                "com.example:excluded:1.0.0",
                "com.example:shared:1.0.0"), nodeStrings(graph));
        assertEquals(List.of(
                "com.example:other:1.0.0->com.example:excluded:1.0.0",
                "com.example:root:1.0.0->com.example:shared:1.0.0"), edgeStrings(graph));
    }

    @Test
    void skipsTransitiveTestAndProvidedDependenciesFromMainGraph() {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:root:1.0.0", pom("com.example", "root", "1.0.0", List.of(
                testDependency("com.example", "test-helper", "1.0.0"),
                providedDependency("com.example", "provided-api", "1.0.0"))));

        ResolutionGraph graph = traverser(source).traverse(List.of(direct("com.example", "root", "1.0.0")));

        assertEquals(List.of("com.example:root:1.0.0"), nodeStrings(graph));
    }

    @Test
    void directTestDependenciesKeepCompileAndRuntimeTransitivesInTestScope() {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:test-root:1.0.0", pom("com.example", "test-root", "1.0.0", List.of(
                dependency("com.example", "compile-helper", "1.0.0"),
                runtimeDependency("com.example", "runtime-helper", "1.0.0"),
                testDependency("com.example", "test-helper", "1.0.0"))));
        source.put("com.example:compile-helper:1.0.0", pom("com.example", "compile-helper", "1.0.0", List.of()));
        source.put("com.example:runtime-helper:1.0.0", pom("com.example", "runtime-helper", "1.0.0", List.of()));

        ResolutionGraph graph = traverser(source).traverse(List.of(directTest("com.example", "test-root", "1.0.0")));

        assertEquals(List.of(
                "com.example:test-root:1.0.0",
                "com.example:compile-helper:1.0.0",
                "com.example:runtime-helper:1.0.0"), nodeStrings(graph));
        assertEquals(List.of(DependencyScope.TEST, DependencyScope.TEST), graph.edges().stream()
                .map(edge -> edge.request().scope())
                .toList());
    }

    @Test
    void expandsSamePackageVersionSeparatelyPerScope() {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:main-root:1.0.0", pom("com.example", "main-root", "1.0.0", List.of(
                dependency("com.example", "shared", "1.0.0"))));
        source.put("com.example:test-root:1.0.0", pom("com.example", "test-root", "1.0.0", List.of(
                dependency("com.example", "shared", "1.0.0"))));
        source.put("com.example:shared:1.0.0", pom("com.example", "shared", "1.0.0", List.of(
                dependency("com.example", "shared-child", "1.0.0"))));
        source.put("com.example:shared-child:1.0.0", pom("com.example", "shared-child", "1.0.0", List.of()));

        ResolutionGraph graph = traverser(source).traverse(List.of(
                direct("com.example", "main-root", "1.0.0"),
                directTest("com.example", "test-root", "1.0.0")));

        assertEquals(List.of(
                "com.example:main-root:1.0.0",
                "com.example:test-root:1.0.0",
                "com.example:shared:1.0.0",
                "com.example:shared-child:1.0.0"), nodeStrings(graph));
        assertEquals(List.of(DependencyScope.COMPILE, DependencyScope.TEST), graph.edges().stream()
                .filter(edge -> edge.to().packageId().equals(new PackageId("com.example", "shared-child")))
                .map(edge -> edge.request().scope())
                .toList());
        assertEquals(List.of(DependencyScope.COMPILE, DependencyScope.TEST), graph.edges().stream()
                .filter(edge -> edge.to().packageId().equals(new PackageId("com.example", "shared-child")))
                .map(edge -> edge.sourceScope())
                .toList());
        assertEquals(1, source.loadCount("com.example:shared:1.0.0"));
    }

    @Test
    void appliesUniversalWildcardExclusionThroughDeclaringEdge() {
        assertWildcardExclusionPrunesSharedThroughLeftEdge("*", "*");
    }

    @Test
    void appliesGroupWildcardExclusionThroughDeclaringEdge() {
        assertWildcardExclusionPrunesSharedThroughLeftEdge("com.example", "*");
    }

    @Test
    void appliesArtifactWildcardExclusionThroughDeclaringEdge() {
        assertWildcardExclusionPrunesSharedThroughLeftEdge("*", "shared");
    }

    private void assertWildcardExclusionPrunesSharedThroughLeftEdge(
            String excludedGroupId,
            String excludedArtifactId) {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:root:1.0.0", pom("com.example", "root", "1.0.0", List.of(
                dependencyWithExclusion("com.example", "left", "1.0.0", excludedGroupId, excludedArtifactId),
                dependency("com.example", "right", "1.0.0"))));
        source.put("com.example:left:1.0.0", pom("com.example", "left", "1.0.0", List.of(dependency("com.example", "shared", "1.0.0"))));
        source.put("com.example:right:1.0.0", pom("com.example", "right", "1.0.0", List.of(dependency("com.example", "shared", "1.0.0"))));
        source.put("com.example:shared:1.0.0", pom("com.example", "shared", "1.0.0", List.of()));

        ResolutionGraph graph = traverser(source).traverse(List.of(direct("com.example", "root", "1.0.0")));

        assertEquals(List.of(
                "com.example:root:1.0.0",
                "com.example:left:1.0.0",
                "com.example:right:1.0.0",
                "com.example:shared:1.0.0"), nodeStrings(graph));
        assertEquals(List.of(
                "com.example:root:1.0.0->com.example:left:1.0.0",
                "com.example:root:1.0.0->com.example:right:1.0.0",
                "com.example:right:1.0.0->com.example:shared:1.0.0"), edgeStrings(graph));
    }

    @Test
    void appliesExclusionOnlyThroughDeclaringEdge() {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:root:1.0.0", pom("com.example", "root", "1.0.0", List.of(
                dependencyWithExclusion("com.example", "left", "1.0.0", "com.example", "shared"),
                dependency("com.example", "right", "1.0.0"))));
        source.put("com.example:left:1.0.0", pom("com.example", "left", "1.0.0", List.of(dependency("com.example", "shared", "1.0.0"))));
        source.put("com.example:right:1.0.0", pom("com.example", "right", "1.0.0", List.of(dependency("com.example", "shared", "1.0.0"))));
        source.put("com.example:shared:1.0.0", pom("com.example", "shared", "1.0.0", List.of()));

        ResolutionGraph graph = traverser(source).traverse(List.of(direct("com.example", "root", "1.0.0")));

        assertEquals(List.of(
                "com.example:root:1.0.0",
                "com.example:left:1.0.0",
                "com.example:right:1.0.0",
                "com.example:shared:1.0.0"), nodeStrings(graph));
        assertEquals(List.of(
                "com.example:root:1.0.0->com.example:left:1.0.0",
                "com.example:root:1.0.0->com.example:right:1.0.0",
                "com.example:right:1.0.0->com.example:shared:1.0.0"), edgeStrings(graph));
    }

    @Test
    void rootManagedVersionOverridesTransitiveRequestBeforeTraversal() {
        PackageId library = new PackageId("com.example", "lib");
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:root:1.0.0", pom("com.example", "root", "1.0.0", List.of(
                dependency("com.example", "lib", "1.0.0"))));
        source.put("com.example:lib:2.0.0", pom("com.example", "lib", "2.0.0", List.of()));

        ResolutionGraph graph = traverser(
                source,
                Map.of(library, new ManagedVersion("2.0.0", "com.example:platform:1.0.0")))
                .traverse(List.of(direct("com.example", "root", "1.0.0")));

        assertEquals(List.of("com.example:root:1.0.0", "com.example:lib:2.0.0"), nodeStrings(graph));
        assertEquals(List.of("com.example:root:1.0.0->com.example:lib:2.0.0"), edgeStrings(graph));
        assertEquals(0, source.loadCount("com.example:lib:1.0.0"));
        assertEquals(1, source.loadCount("com.example:lib:2.0.0"));
        assertEquals(
                List.of("managed-version: com.example:lib -> 2.0.0 from com.example:platform:1.0.0"),
                graph.policyEffects().stream()
                        .map(effect -> effect.policy())
                        .toList());
    }

    @Test
    void rootManagedVersionBypassesUnsupportedTransitiveRange() {
        PackageId library = new PackageId("com.example", "lib");
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:root:1.0.0", pom("com.example", "root", "1.0.0", List.of(
                dependency("com.example", "lib", "[1.0,2.0)"))));
        source.put("com.example:lib:2.0.0", pom("com.example", "lib", "2.0.0", List.of()));

        ResolutionGraph graph = traverser(
                source,
                Map.of(library, new ManagedVersion("2.0.0", "com.example:platform:1.0.0")))
                .traverse(List.of(direct("com.example", "root", "1.0.0")));

        assertEquals(List.of("com.example:root:1.0.0", "com.example:lib:2.0.0"), nodeStrings(graph));
        assertEquals(0, source.loadCount("com.example:lib:[1.0,2.0)"));
    }

    @Test
    void strictConstraintBypassesUnsupportedTransitiveRange() {
        PackageId library = new PackageId("com.example", "lib");
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:root:1.0.0", pom("com.example", "root", "1.0.0", List.of(
                dependency("com.example", "lib", "[1.0,2.0)"))));
        source.put("com.example:lib:1.0", pom("com.example", "lib", "1.0", List.of()));

        ResolutionGraph graph = new DependencyGraphTraverser(
                source,
                new DependencyPolicySettings(
                        List.of(),
                        Map.of("com.example:lib", new DependencyConstraint(
                                "com.example:lib",
                                "1.0",
                                DependencyConstraintKind.STRICT,
                                Optional.empty()))),
                Map.of())
                .traverse(List.of(direct("com.example", "root", "1.0.0")));

        assertEquals(List.of("com.example:root:1.0.0", "com.example:lib:1.0"), nodeStrings(graph));
        assertEquals(0, source.loadCount("com.example:lib:[1.0,2.0)"));
    }

    @Test
    void unsupportedTransitiveRangeHintNamesDependencyConstraintsAndRetryCommand() {
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:root:1.0.0", pom("com.example", "root", "1.0.0", List.of(
                dependency("com.example", "lib", "[1.0,2.0)"))));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> new DependencyGraphTraverser(
                        source,
                        DependencyPolicySettings.defaults(),
                        Map.of(),
                        "zolt resolve --workspace")
                        .traverse(List.of(direct("com.example", "root", "1.0.0"))));

        assertTrue(exception.getMessage().contains("Unsupported transitive dependency version `[1.0,2.0)`"));
        assertTrue(exception.getMessage().contains("[dependencyConstraints]"));
        assertTrue(exception.getMessage()
                .contains("\"com.example:lib\" = { version = \"1.0\", kind = \"strict\" }"));
        assertTrue(exception.getMessage().contains("run `zolt resolve --workspace` again"));
        assertTrue(!exception.getMessage().contains("[dependencyPolicy] constraint"), () -> exception.getMessage());
        assertTrue(!exception.getMessage().contains("[platforms] entry"), () -> exception.getMessage());
    }

}
