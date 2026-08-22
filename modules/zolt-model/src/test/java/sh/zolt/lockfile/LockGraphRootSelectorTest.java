package sh.zolt.lockfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;

final class LockGraphRootSelectorTest {
    @Test
    void usesAuthoredRootsAndSourceComponentsInsteadOfDirectFlagsOrScopeGuesses() {
        LockPackage authored = lockPackage("authored", DependencyScope.COMPILE, false, List.of(ref("stale", DependencyScope.COMPILE)));
        LockPackage staleDirectChild = lockPackage("stale", DependencyScope.COMPILE, true, List.of());
        LockPackage runtime = lockPackage("runtime", DependencyScope.RUNTIME, false, List.of());
        LockPackage test = lockPackage("test", DependencyScope.TEST, false, List.of());
        LockPackage quarkus = lockPackage("quarkus", DependencyScope.QUARKUS_DEPLOYMENT, false, List.of());
        LockPackage toolRoot = lockPackage("tool-root", DependencyScope.TOOL_EXEC, false, List.of(ref("tool-child", DependencyScope.TOOL_EXEC)));
        LockPackage toolChild = lockPackage("tool-child", DependencyScope.TOOL_EXEC, true, List.of());
        LockPackage cycleA = lockPackage("cycle-a", DependencyScope.RUNTIME, false, List.of(ref("cycle-b", DependencyScope.RUNTIME)));
        LockPackage cycleB = lockPackage("cycle-b", DependencyScope.RUNTIME, false, List.of(ref("cycle-a", DependencyScope.RUNTIME)));

        List<String> selected = LockGraphRootSelector.select(
                        List.of(authored, staleDirectChild, runtime, test, quarkus, toolRoot, toolChild, cycleB, cycleA),
                        List.of(root(authored)),
                        "zolt resolve")
                .stream()
                .map(LockDependencyEdge::of)
                .map(LockDependencyEdge::encode)
                .toList();

        assertEquals(List.of(
                ref("authored", DependencyScope.COMPILE),
                ref("cycle-a", DependencyScope.RUNTIME),
                ref("quarkus", DependencyScope.QUARKUS_DEPLOYMENT),
                ref("runtime", DependencyScope.RUNTIME),
                ref("test", DependencyScope.TEST),
                ref("tool-root", DependencyScope.TOOL_EXEC)), selected);
    }

    @Test
    void retainsAnAuthoredRootEvenWhenAnotherPackagePointsAtIt() {
        LockPackage injected = lockPackage("injected", DependencyScope.COMPILE, false, List.of(ref("authored", DependencyScope.COMPILE)));
        LockPackage authored = lockPackage("authored", DependencyScope.COMPILE, false, List.of());

        List<String> selected = LockGraphRootSelector.select(
                        List.of(authored, injected),
                        List.of(root(authored)),
                        "zolt resolve")
                .stream()
                .map(LockDependencyEdge::of)
                .map(LockDependencyEdge::encode)
                .toList();

        assertEquals(List.of(ref("authored", DependencyScope.COMPILE), ref("injected", DependencyScope.COMPILE)), selected);
    }

    @Test
    void closesAMemberSliceOverTheAggregateSoASiblingsOwnEdgesAreNotDangling() {
        LockPackage sibling = lockPackage(
                "sibling", DependencyScope.COMPILE, true, List.of(ref("sibling-external", DependencyScope.COMPILE)));
        LockPackage siblingExternal = lockPackage("sibling-external", DependencyScope.COMPILE, false, List.of());
        LockPackage unrelated = lockPackage("unrelated", DependencyScope.COMPILE, false, List.of());

        List<String> selected = LockGraphRootSelector.select(
                        List.of(sibling),
                        List.of(root(sibling)),
                        List.of(sibling, siblingExternal, unrelated),
                        "zolt resolve --workspace")
                .stream()
                .map(LockDependencyEdge::of)
                .map(LockDependencyEdge::encode)
                .toList();

        assertEquals(List.of(ref("sibling", DependencyScope.COMPILE)), selected);
    }

    @Test
    void refusesAnAuthoredRootMissingFromTheProjectedGraph() {
        LockPackage missing = lockPackage("missing", DependencyScope.COMPILE, false, List.of());

        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> LockGraphRootSelector.select(List.of(), List.of(root(missing)), "zolt resolve --workspace"));

        assertTrue(exception.getMessage().contains("selects 0 packages"));
        assertTrue(exception.getMessage().contains("zolt resolve --workspace"));
    }

    @Test
    void ignoresPublishOnlyRootsWhenSelectingMaterializedGraphRoots() {
        LockDependencyRoot publishOnly = new LockDependencyRoot(
                ".",
                new PackageId("com.example", "metadata-only"),
                "1.0.0",
                null,
                DependencyLane.API,
                Optional.empty(),
                false,
                true);

        assertEquals(List.of(), LockGraphRootSelector.select(List.of(), List.of(publishOnly), "zolt resolve"));
    }

    private static LockDependencyRoot root(LockPackage lockPackage) {
        return new LockDependencyRoot(
                ".",
                lockPackage.packageId(),
                lockPackage.version(),
                LockArtifactVariant.of(lockPackage),
                DependencyLane.IMPLEMENTATION,
                Optional.of(lockPackage.scope()),
                false,
                false);
    }

    private static LockPackage lockPackage(
            String artifactId,
            DependencyScope scope,
            boolean direct,
            List<String> dependencies) {
        return new LockPackage(
                new PackageId("com.example", artifactId),
                "1.0.0",
                "maven-central",
                scope,
                direct,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dependencies);
    }

    private static String ref(String artifactId, DependencyScope scope) {
        return "com.example:" + artifactId + ":1.0.0:jar:" + scope.lockfileName();
    }
}
