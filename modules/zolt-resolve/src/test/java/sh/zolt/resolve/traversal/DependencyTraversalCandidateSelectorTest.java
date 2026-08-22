package sh.zolt.resolve.traversal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.repository.RawPomDependency;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyConstraintKind;
import sh.zolt.resolve.request.DependencyExclusion;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import sh.zolt.resolve.request.RequestOrigin;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DependencyTraversalCandidateSelectorTest {
    private static final PackageNode ROOT = new PackageNode(new PackageId("com.example", "root"), "1.0.0");
    private final DependencyTraversalCandidateSelector selector = selector(List.of(), Map.of());

    @Test
    void selectsTransitiveDependencyWithoutRepositoryIo() {
        DependencyTraversalSelection selection = selector.select(candidate(
                DependencyTraversalItem.direct(request(DependencyScope.COMPILE)),
                dependency("com.example", "runtime-lib", "1.0.0", DependencyScope.RUNTIME, false)));

        DependencyTraversalItem selected = selection.selectedItem().orElseThrow();

        assertEquals(List.of(), selection.policyEffects());
        assertEquals(ROOT, selected.parent().orElseThrow());
        assertEquals(new PackageId("com.example", "runtime-lib"), selected.request().packageId());
        assertEquals("1.0.0", selected.request().requestedVersion());
        assertEquals(DependencyScope.RUNTIME, selected.request().scope());
        assertEquals(RequestOrigin.TRANSITIVE, selected.request().origin());
        assertEquals("non-optional transitive dependency", selected.decision().reason());
    }

    @Test
    void skipsOptionalAndNonRuntimeScopesAsPureDecisions() {
        DependencyTraversalSelection optional = selector.select(candidate(
                DependencyTraversalItem.direct(request(DependencyScope.COMPILE)),
                dependency("com.example", "optional-lib", "1.0.0", DependencyScope.COMPILE, true)));
        DependencyTraversalSelection testScope = selector.select(candidate(
                DependencyTraversalItem.direct(request(DependencyScope.COMPILE)),
                dependency("com.example", "test-lib", "1.0.0", DependencyScope.TEST, false)));

        assertTrue(optional.selectedItem().isEmpty());
        assertEquals(List.of(), optional.policyEffects());
        assertTrue(testScope.selectedItem().isEmpty());
        assertEquals(List.of(), testScope.policyEffects());
    }

    @Test
    void returnsPolicyEffectsForEdgeAndGlobalExclusions() {
        DependencyTraversalCandidateSelector globalSelector = selector(
                List.of(new DependencyGlobalExclusion(
                        new DependencyExclusion("com.example", "global-lib"),
                        Optional.of("use platform replacement"))),
                Map.of());

        DependencyTraversalSelection edge = selector.select(candidate(
                DependencyTraversalItem.direct(new DependencyRequest(
                        ROOT.packageId(),
                        ROOT.selectedVersion(),
                        DependencyScope.COMPILE,
                        RequestOrigin.DIRECT,
                        List.of(new DependencyExclusion("com.example", "edge-lib")))),
                dependency("com.example", "edge-lib", "1.0.0", DependencyScope.COMPILE, false)));
        DependencyTraversalSelection global = globalSelector.select(candidate(
                DependencyTraversalItem.direct(request(DependencyScope.COMPILE)),
                dependency("com.example", "global-lib", "1.0.0", DependencyScope.COMPILE, false)));

        assertTrue(edge.selectedItem().isEmpty());
        assertEquals("edge-exclusion", edge.policyEffects().getFirst().kind());
        assertEquals(
                "dependency edge exclusion from com.example:root:1.0.0 to com.example:edge-lib",
                edge.policyEffects().getFirst().policy());
        assertTrue(global.selectedItem().isEmpty());
        assertEquals("global-exclusion", global.policyEffects().getFirst().kind());
        assertEquals(
                "[dependencies.policy].deny com.example:global-lib (use platform replacement)",
                global.policyEffects().getFirst().policy());
    }

    @Test
    void wildcardEdgeExclusionsMatchByMavenSegmentSemantics() {
        assertEdgeExcluded("*", "*");
        assertEdgeExcluded("com.example", "*");
        assertEdgeExcluded("*", "edge-lib");

        DependencyTraversalSelection nonMatching = selector.select(candidate(
                DependencyTraversalItem.direct(new DependencyRequest(
                        ROOT.packageId(),
                        ROOT.selectedVersion(),
                        DependencyScope.COMPILE,
                        RequestOrigin.DIRECT,
                        List.of(new DependencyExclusion("other", "x")))),
                dependency("com.example", "edge-lib", "1.0.0", DependencyScope.COMPILE, false)));

        assertTrue(nonMatching.selectedItem().isPresent());
        assertEquals(List.of(), nonMatching.policyEffects());
    }

    private void assertEdgeExcluded(String excludedGroupId, String excludedArtifactId) {
        DependencyTraversalSelection selection = selector.select(candidate(
                DependencyTraversalItem.direct(new DependencyRequest(
                        ROOT.packageId(),
                        ROOT.selectedVersion(),
                        DependencyScope.COMPILE,
                        RequestOrigin.DIRECT,
                        List.of(new DependencyExclusion(excludedGroupId, excludedArtifactId)))),
                dependency("com.example", "edge-lib", "1.0.0", DependencyScope.COMPILE, false)));

        assertTrue(selection.selectedItem().isEmpty());
        assertEquals("edge-exclusion", selection.policyEffects().getFirst().kind());
    }

    @Test
    void strictConstraintOverridesRequestedVersionAndReturnsPolicyEffect() {
        PackageId library = new PackageId("com.example", "lib");
        DependencyTraversalCandidateSelector constrained = selector(
                List.of(),
                Map.of(library, new DependencyConstraint(
                        "com.example:lib",
                        "2.0.0",
                        DependencyConstraintKind.STRICT,
                        Optional.of("baseline"))));

        DependencyTraversalSelection selection = constrained.select(candidate(
                DependencyTraversalItem.direct(request(DependencyScope.COMPILE)),
                dependency("com.example", "lib", "1.0.0", DependencyScope.COMPILE, false)));

        assertEquals("2.0.0", selection.selectedItem().orElseThrow().request().requestedVersion());
        assertEquals("strict-version", selection.policyEffects().getFirst().kind());
        assertEquals(
                "strict-version: com.example:lib requested 1.0.0 -> 2.0.0 (baseline)",
                selection.policyEffects().getFirst().policy());
    }

    @Test
    void rootManagedVersionOverridesRequestedVersionAndReturnsPolicyEffect() {
        PackageId library = new PackageId("com.example", "lib");
        DependencyTraversalCandidateSelector managed = selector(
                List.of(),
                Map.of(),
                Map.of(library, new ManagedVersion("2.0.0", "com.example:platform:1.0.0")));

        DependencyTraversalSelection selection = managed.select(candidate(
                DependencyTraversalItem.direct(request(DependencyScope.COMPILE)),
                dependency("com.example", "lib", "1.0.0", DependencyScope.COMPILE, false)));

        assertEquals("2.0.0", selection.selectedItem().orElseThrow().request().requestedVersion());
        assertEquals("managed-version", selection.policyEffects().getFirst().kind());
        assertEquals(Optional.of("1.0.0"), selection.policyEffects().getFirst().requestedVersion());
        assertEquals(Optional.of("com.example:root:1.0.0"), selection.policyEffects().getFirst().source());
        assertEquals(
                "managed-version: com.example:lib -> 2.0.0 from com.example:platform:1.0.0",
                selection.policyEffects().getFirst().policy());
    }

    @Test
    void strictConstraintWinsOverRootManagedVersion() {
        PackageId library = new PackageId("com.example", "lib");
        DependencyTraversalCandidateSelector managedAndStrict = selector(
                List.of(),
                Map.of(library, new DependencyConstraint(
                        "com.example:lib",
                        "3.0.0",
                        DependencyConstraintKind.STRICT,
                        Optional.of("baseline"))),
                Map.of(library, new ManagedVersion("2.0.0", "com.example:platform:1.0.0")));

        DependencyTraversalSelection selection = managedAndStrict.select(candidate(
                DependencyTraversalItem.direct(request(DependencyScope.COMPILE)),
                dependency("com.example", "lib", "1.0.0", DependencyScope.COMPILE, false)));

        assertEquals("3.0.0", selection.selectedItem().orElseThrow().request().requestedVersion());
        assertEquals(List.of("strict-version"), selection.policyEffects().stream()
                .map(effect -> effect.kind())
                .toList());
    }

    @Test
    void rootManagedVersionSuppliesMissingTransitiveVersion() {
        PackageId library = new PackageId("com.example", "missing-version");
        DependencyTraversalCandidateSelector managed = selector(
                List.of(),
                Map.of(),
                Map.of(library, new ManagedVersion("2.0.0", "com.example:platform:1.0.0")));

        DependencyTraversalSelection selection = managed.select(candidate(
                DependencyTraversalItem.direct(request(DependencyScope.COMPILE)),
                new NormalizedDependency(
                        new RawPomDependency(
                                "com.example",
                                "missing-version",
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                false,
                                List.of()),
                        DependencyScope.COMPILE,
                        false,
                        List.of())));

        assertEquals("2.0.0", selection.selectedItem().orElseThrow().request().requestedVersion());
        assertEquals("managed-version", selection.policyEffects().getFirst().kind());
        assertTrue(selection.policyEffects().getFirst().requestedVersion().isEmpty());
    }

    @Test
    void rootManagedVersionDoesNotOverrideClassifiedOrNonJarTransitive() {
        PackageId library = new PackageId("com.example", "lib");
        DependencyTraversalCandidateSelector managed = selector(
                List.of(),
                Map.of(),
                Map.of(library, new ManagedVersion("2.0.0", "com.example:platform:1.0.0")));

        DependencyTraversalSelection classified = managed.select(candidate(
                DependencyTraversalItem.direct(request(DependencyScope.COMPILE)),
                new NormalizedDependency(
                        new RawPomDependency(
                                "com.example",
                                "lib",
                                Optional.of("1.0.0"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of("linux-x86_64"),
                                false,
                                List.of()),
                        DependencyScope.COMPILE,
                        false,
                        List.of())));
        DependencyTraversalSelection nonJar = managed.select(candidate(
                DependencyTraversalItem.direct(request(DependencyScope.COMPILE)),
                new NormalizedDependency(
                        new RawPomDependency(
                                "com.example",
                                "lib",
                                Optional.of("1.0.0"),
                                Optional.empty(),
                                Optional.of("zip"),
                                Optional.empty(),
                                false,
                                List.of()),
                        DependencyScope.COMPILE,
                        false,
                        List.of())));

        assertEquals("1.0.0", classified.selectedItem().orElseThrow().request().requestedVersion());
        assertTrue(classified.policyEffects().isEmpty());
        assertEquals("1.0.0", nonJar.selectedItem().orElseThrow().request().requestedVersion());
        assertTrue(nonJar.policyEffects().isEmpty());
    }

    @Test
    void rootManagedVersionOmitsEffectWhenTransitiveAlreadyDeclaresManagedVersion() {
        PackageId library = new PackageId("com.example", "lib");
        DependencyTraversalCandidateSelector managed = selector(
                List.of(),
                Map.of(),
                Map.of(library, new ManagedVersion("2.0.0", "com.example:platform:1.0.0")));

        DependencyTraversalSelection selection = managed.select(candidate(
                DependencyTraversalItem.direct(request(DependencyScope.COMPILE)),
                dependency("com.example", "lib", "2.0.0", DependencyScope.COMPILE, false)));

        assertEquals("2.0.0", selection.selectedItem().orElseThrow().request().requestedVersion());
        assertTrue(selection.policyEffects().isEmpty());
    }

    @Test
    void versionlessDependencyWithoutConstraintKeepsDiagnosticShape() {
        GraphTraversalException exception = assertThrows(
                GraphTraversalException.class,
                () -> selector.select(candidate(
                        DependencyTraversalItem.direct(request(DependencyScope.COMPILE)),
                        new NormalizedDependency(
                                new RawPomDependency(
                                        "com.example",
                                        "missing-version",
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        false,
                                        List.of()),
                                DependencyScope.COMPILE,
                                false,
                                List.of()))));

        assertEquals(
                "Dependency com.example:missing-version from com.example:root:1.0.0 does not declare or inherit a version.",
                exception.getMessage());
    }

    private static DependencyTraversalCandidateSelector selector(
            List<DependencyGlobalExclusion> exclusions,
            Map<PackageId, DependencyConstraint> constraints) {
        return selector(exclusions, constraints, Map.of());
    }

    private static DependencyTraversalCandidateSelector selector(
            List<DependencyGlobalExclusion> exclusions,
            Map<PackageId, DependencyConstraint> constraints,
            Map<PackageId, ManagedVersion> managedVersions) {
        return new DependencyTraversalCandidateSelector(
                new DependencyTraversalPolicy(),
                new DependencyTransitiveScopeSelector(),
                exclusions,
                constraints,
                managedVersions,
                "zolt resolve",
                sh.zolt.resolve.SnapshotAllowance.none());
    }

    private static DependencyTraversalCandidate candidate(
            DependencyTraversalItem item,
            NormalizedDependency dependency) {
        return new DependencyTraversalCandidate(item, ROOT, dependency);
    }

    private static DependencyRequest request(DependencyScope scope) {
        return new DependencyRequest(ROOT.packageId(), ROOT.selectedVersion(), scope, RequestOrigin.DIRECT);
    }

    private static NormalizedDependency dependency(
            String groupId,
            String artifactId,
            String version,
            DependencyScope scope,
            boolean optional) {
        return new NormalizedDependency(
                new RawPomDependency(
                        groupId,
                        artifactId,
                        Optional.of(version),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        optional,
                        List.of()),
                scope,
                optional,
                List.of());
    }
}
