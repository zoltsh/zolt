package sh.zolt.lockfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;

final class ZoltLockfileDependencyRootTest {
    private static final PackageId PACKAGE = new PackageId("com.example", "library");

    @Test
    void dependencyRootsAreImmutableAndSelectAnExactPackageIdentity() {
        List<LockDependencyRoot> roots = new ArrayList<>(List.of(root("1.2.0", DependencyScope.COMPILE)));

        ZoltLockfile lockfile = lockfile(List.of(lockPackage("1.2.0", DependencyScope.COMPILE)), roots);
        roots.clear();

        assertEquals(1, lockfile.dependencyRoots().size());
        assertThrows(UnsupportedOperationException.class, () -> lockfile.dependencyRoots().clear());
    }

    @Test
    void standaloneRootSelectsAnUnattributedStandalonePackage() {
        LockDependencyRoot standalone = root("1.2.0", DependencyScope.COMPILE);

        assertEquals(
                List.of(standalone),
                lockfile(
                                List.of(lockPackage("1.2.0", DependencyScope.COMPILE)),
                                List.of(standalone))
                        .dependencyRoots());
    }

    @Test
    void rejectsMemberQualifiedRootSelectingAnUnattributedPackage() {
        LockDependencyRoot workspaceRoot = new LockDependencyRoot(
                "apps/api",
                PACKAGE,
                "1.2.0",
                null,
                DependencyLane.IMPLEMENTATION,
                Optional.of(DependencyScope.COMPILE),
                false,
                false);

        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> lockfile(
                        List.of(lockPackage("1.2.0", DependencyScope.COMPILE)),
                        List.of(workspaceRoot)));

        assertTrue(exception.getMessage().contains("selects missing package"));
    }

    @Test
    void rejectsDuplicateSemanticRoots() {
        LockDependencyRoot root = root("1.2.0", DependencyScope.COMPILE);

        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> lockfile(List.of(lockPackage("1.2.0", DependencyScope.COMPILE)), List.of(root, root)));

        assertTrue(exception.getMessage().contains("duplicate dependency roots"));
    }

    @Test
    void rejectsAResolvedRootWhoseExactVersionIsMissing() {
        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> lockfile(
                        List.of(lockPackage("1.3.0", DependencyScope.COMPILE)),
                        List.of(root("1.2.0", DependencyScope.COMPILE))));

        assertTrue(exception.getMessage().contains("selects missing package"));
        assertTrue(exception.getMessage().contains("com.example:library:1.2.0:jar:compile"));
    }

    @Test
    void rejectsAResolvedRootWhoseExactVariantIsMissing() {
        LockDependencyRoot classified = new LockDependencyRoot(
                ".",
                PACKAGE,
                "1.2.0",
                new LockArtifactVariant("jar", Optional.of("tests")),
                DependencyLane.API,
                Optional.of(DependencyScope.COMPILE),
                false,
                false);

        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> lockfile(
                        List.of(lockPackage("1.2.0", DependencyScope.COMPILE)),
                        List.of(classified)));

        assertTrue(exception.getMessage().contains("com.example:library:1.2.0:jar|tests:compile"));
    }

    @Test
    void rejectsAResolvedRootWhoseExactScopeIsMissing() {
        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> lockfile(
                        List.of(lockPackage("1.2.0", DependencyScope.TEST)),
                        List.of(root("1.2.0", DependencyScope.COMPILE))));

        assertTrue(exception.getMessage().contains("com.example:library:1.2.0:jar:compile"));
    }

    @Test
    void rejectsAWorkspaceRootWhosePackageBelongsOnlyToAnotherMember() {
        LockDependencyRoot apiRoot = new LockDependencyRoot(
                "apps/api",
                PACKAGE,
                "1.2.0",
                null,
                DependencyLane.IMPLEMENTATION,
                Optional.of(DependencyScope.COMPILE),
                false,
                false);
        LockPackage workerPackage = new LockPackage(
                PACKAGE,
                "1.2.0",
                "maven-central",
                DependencyScope.COMPILE,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of("apps/worker"));

        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> lockfile(List.of(workerPackage), List.of(apiRoot)));

        assertTrue(exception.getMessage().contains("selects missing package"));
        assertTrue(exception.getMessage().contains("apps/api:implementation:com.example:library:jar"));
    }

    @Test
    void acceptsAWorkspaceRootWhosePackageIncludesItsMember() {
        LockDependencyRoot apiRoot = new LockDependencyRoot(
                "apps/api",
                PACKAGE,
                "1.2.0",
                null,
                DependencyLane.IMPLEMENTATION,
                Optional.of(DependencyScope.COMPILE),
                false,
                false);
        LockPackage sharedPackage = new LockPackage(
                PACKAGE,
                "1.2.0",
                "maven-central",
                DependencyScope.COMPILE,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of("apps/api", "apps/worker"));

        assertEquals(List.of(apiRoot), lockfile(List.of(sharedPackage), List.of(apiRoot)).dependencyRoots());
    }

    @Test
    void authoredLaneAndResolvedScopeRemainIndependent() {
        LockDependencyRoot apiRuntime = new LockDependencyRoot(
                ".",
                PACKAGE,
                "1.2.0",
                null,
                DependencyLane.API,
                Optional.of(DependencyScope.RUNTIME),
                false,
                false);

        ZoltLockfile lockfile = lockfile(
                List.of(lockPackage("1.2.0", DependencyScope.RUNTIME)),
                List.of(apiRuntime));

        assertEquals(DependencyLane.API, lockfile.dependencyRoots().getFirst().lane());
        assertEquals(DependencyScope.RUNTIME, lockfile.dependencyRoots().getFirst().resolvedScope().orElseThrow());
    }

    @Test
    void rejectsTheSameVariantInTwoOrdinaryLanes() {
        LockDependencyRoot api = root("1.2.0", DependencyScope.COMPILE);
        LockDependencyRoot implementation = new LockDependencyRoot(
                ".",
                PACKAGE,
                "1.2.0",
                null,
                DependencyLane.IMPLEMENTATION,
                Optional.of(DependencyScope.COMPILE),
                false,
                false);

        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> lockfile(
                        List.of(lockPackage("1.2.0", DependencyScope.COMPILE)),
                        List.of(api, implementation)));

        assertTrue(exception.getMessage().contains("in both API and IMPLEMENTATION lanes"));
    }

    @Test
    void allowsOrdinaryAndBothProcessorLanesForTheSameVariant() {
        LockDependencyRoot processor = new LockDependencyRoot(
                ".", PACKAGE, "1.2.0", null, DependencyLane.PROCESSOR,
                Optional.of(DependencyScope.PROCESSOR), false, false);
        LockDependencyRoot testProcessor = new LockDependencyRoot(
                ".", PACKAGE, "1.2.0", null, DependencyLane.TEST_PROCESSOR,
                Optional.of(DependencyScope.TEST_PROCESSOR), false, false);

        ZoltLockfile lockfile = lockfile(
                List.of(
                        lockPackage("1.2.0", DependencyScope.COMPILE),
                        lockPackage("1.2.0", DependencyScope.PROCESSOR),
                        lockPackage("1.2.0", DependencyScope.TEST_PROCESSOR)),
                List.of(root("1.2.0", DependencyScope.COMPILE), processor, testProcessor));

        assertEquals(3, lockfile.dependencyRoots().size());
    }

    @Test
    void rejectsNonPortableMavenRootIdentity() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new LockDependencyRoot(
                        ".",
                        new PackageId("bad id", "library"),
                        "1.2.0",
                        null,
                        DependencyLane.API,
                        Optional.of(DependencyScope.COMPILE),
                        false,
                        false));

        assertTrue(exception.getMessage().contains("Invalid dependency coordinate"));
    }

    @Test
    void publishOnlyRootIntentionallyHasNoSelectedPackage() {
        LockDependencyRoot publishOnly = new LockDependencyRoot(
                ".",
                PACKAGE,
                "1.2.0",
                null,
                DependencyLane.RUNTIME,
                Optional.empty(),
                false,
                true);

        assertEquals(List.of(publishOnly), lockfile(List.of(), List.of(publishOnly)).dependencyRoots());
    }

    @Test
    void preV7ModelCannotCarryDependencyRoots() {
        assertThrows(IllegalArgumentException.class, () -> new ZoltLockfile(
                6,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(lockPackage("1.2.0", DependencyScope.COMPILE)),
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                List.of(root("1.2.0", DependencyScope.COMPILE))));
    }

    @Test
    void workspaceFingerprintCopyRetainsDependencyRoots() {
        ZoltLockfile lockfile = lockfile(
                List.of(lockPackage("1.2.0", DependencyScope.COMPILE)),
                List.of(root("1.2.0", DependencyScope.COMPILE)));

        assertEquals(
                lockfile.dependencyRoots(),
                lockfile.withWorkspaceResolutionInputFingerprint(Optional.of("sha256:workspace"))
                        .dependencyRoots());
    }

    private static ZoltLockfile lockfile(
            List<LockPackage> packages,
            List<LockDependencyRoot> roots) {
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                packages,
                List.of(),
                List.of(),
                List.of(),
                roots);
    }

    private static LockDependencyRoot root(String version, DependencyScope scope) {
        return new LockDependencyRoot(
                ".",
                PACKAGE,
                version,
                null,
                DependencyLane.API,
                Optional.of(scope),
                false,
                false);
    }

    private static LockPackage lockPackage(String version, DependencyScope scope) {
        return new LockPackage(
                PACKAGE,
                version,
                "maven-central",
                scope,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }
}
