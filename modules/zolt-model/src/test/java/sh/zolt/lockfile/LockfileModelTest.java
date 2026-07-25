package sh.zolt.lockfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LockfileModelTest {
    @Test
    void changedInputsNamesOnlyCandidateInputsWithDifferentFingerprints() {
        ZoltLockfile existing = lockfileWithInputs(List.of(
                "zolt.toml=aaa",
                "workspace.toml=unchanged",
                "ignored-missing-separator",
                "=ignored-empty-name",
                "dependency-policy=old"));
        ZoltLockfile candidate = lockfileWithInputs(List.of(
                "zolt.toml=bbb",
                "workspace.toml=unchanged",
                "dependency-policy=new",
                "missing-in-existing=fresh"));

        assertEquals(
                " Changed inputs: zolt.toml, dependency-policy, missing-in-existing.",
                LockfileFreshnessSummary.changedInputs(existing, candidate));
    }

    @Test
    void changedInputsIsEmptyWhenFingerprintsAreUnavailableOrUnchanged() {
        assertEquals("", LockfileFreshnessSummary.changedInputs(lockfileWithInputs(List.of()), lockfileWithInputs(List.of("zolt.toml=aaa"))));
        assertEquals("", LockfileFreshnessSummary.changedInputs(lockfileWithInputs(List.of("zolt.toml=aaa")), lockfileWithInputs(List.of())));
        assertEquals(
                "",
                LockfileFreshnessSummary.changedInputs(
                        lockfileWithInputs(List.of("zolt.toml=aaa")),
                        lockfileWithInputs(List.of("zolt.toml=aaa"))));
    }

    @Test
    void lockfileNormalizesNullableOptionalListsAndCopiesCollections() {
        List<String> inputs = new ArrayList<>(List.of("zolt.toml=aaa"));
        List<LockPackage> packages = new ArrayList<>(List.of(minimalPackage()));
        List<LockConflict> conflicts = new ArrayList<>(List.of(conflict()));

        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                null,
                null,
                inputs,
                packages,
                conflicts,
                null);

        inputs.add("workspace.toml=bbb");
        packages.clear();
        conflicts.clear();

        assertFalse(lockfile.aliasFingerprint().isPresent());
        assertFalse(lockfile.projectResolutionFingerprint().isPresent());
        assertEquals(List.of("zolt.toml=aaa"), lockfile.projectResolutionInputFingerprints());
        assertEquals(1, lockfile.packages().size());
        assertEquals(1, lockfile.conflicts().size());
        assertEquals(List.of(), lockfile.policyEffects());
        assertThrows(UnsupportedOperationException.class, () -> lockfile.packages().clear());
    }

    @Test
    void lockPackageNormalizesNullableOptionalsAndCopiesLists() {
        List<String> dependencies = new ArrayList<>(List.of("org.slf4j:slf4j-api"));

        LockPackage lockPackage = new LockPackage(
                new PackageId("com.example", "demo"),
                "1.0.0",
                "maven-central",
                DependencyScope.COMPILE,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                dependencies,
                null,
                null,
                null,
                null);

        dependencies.add("com.example:late");

        assertFalse(lockPackage.jar().isPresent());
        assertFalse(lockPackage.workspace().isPresent());
        assertEquals(List.of("org.slf4j:slf4j-api"), lockPackage.dependencies());
        assertEquals(List.of(), lockPackage.members());
        assertEquals(List.of(), lockPackage.exportedBy());
        assertEquals(List.of(), lockPackage.policies());
        assertThrows(UnsupportedOperationException.class, () -> lockPackage.dependencies().add("com.example:other"));
    }

    @Test
    void lockConflictAndPolicyEffectNormalizeCollectionsAndOptionals() {
        List<String> requested = new ArrayList<>(List.of("1.0.0", "2.0.0"));
        LockConflict conflict = new LockConflict(
                new PackageId("com.example", "demo"),
                "2.0.0",
                requested,
                ConflictSelectionReason.NEWEST_VERSION);
        LockPolicyEffect effect = new LockPolicyEffect(
                "reject",
                new PackageId("com.example", "demo"),
                null,
                null,
                "no vulnerable versions");

        requested.add("3.0.0");

        assertEquals(List.of("1.0.0", "2.0.0"), conflict.requestedVersions());
        assertThrows(UnsupportedOperationException.class, () -> conflict.requestedVersions().clear());
        assertFalse(conflict.toolGroup().isPresent());
        assertFalse(effect.requestedVersion().isPresent());
        assertFalse(effect.source().isPresent());
    }

    @Test
    void lockConflictNormalizesAndRetainsToolGroupAttribution() {
        LockConflict nullToolGroup = new LockConflict(
                new PackageId("com.example", "demo"),
                "2.0.0",
                List.of("1.0.0", "2.0.0"),
                ConflictSelectionReason.NEWEST_VERSION,
                null);
        LockConflict namedToolGroup = new LockConflict(
                new PackageId("com.example", "demo"),
                "2.0.0",
                List.of("1.0.0", "2.0.0"),
                ConflictSelectionReason.NEWEST_VERSION,
                Optional.of("codegen"));

        assertFalse(nullToolGroup.toolGroup().isPresent());
        assertEquals(Optional.of("codegen"), namedToolGroup.toolGroup());
    }

    @Test
    void memberQualifiedGraphMustCoverEveryAttributedMember() {
        LockPackage lockPackage = new LockPackage(
                new PackageId("com.example", "root"),
                "1.0.0",
                "maven-central",
                DependencyScope.COMPILE,
                false,
                Optional.of("root.jar"),
                Optional.of("root.pom"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of("modules/core", "modules/worker"),
                List.of(),
                List.of(),
                List.of());
        LockMemberGraph coreOnly = new LockMemberGraph(
                "modules/core",
                lockPackage.packageId(),
                lockPackage.version(),
                LockArtifactVariant.defaultVariant(),
                lockPackage.scope(),
                List.of(),
                List.of());

        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> new ZoltLockfile(
                        ZoltLockfile.CURRENT_VERSION,
                        Optional.empty(),
                        Optional.empty(),
                        List.of(),
                        List.of(lockPackage),
                        List.of(),
                        List.of(),
                        List.of(coreOnly)));

        assertTrue(exception.getMessage().contains("modules/worker"));
        assertTrue(exception.getMessage().contains(
                "zolt resolve --workspace"));
    }

    private static ZoltLockfile lockfileWithInputs(List<String> inputs) {
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                inputs,
                List.of(),
                List.of(),
                List.of());
    }

    private static LockPackage minimalPackage() {
        return new LockPackage(
                new PackageId("com.example", "demo"),
                "1.0.0",
                "maven-central",
                DependencyScope.COMPILE,
                true,
                Optional.of("demo.jar"),
                Optional.of("demo.pom"),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    private static LockConflict conflict() {
        return new LockConflict(
                new PackageId("com.example", "demo"),
                "1.0.0",
                List.of("1.0.0"),
                ConflictSelectionReason.DIRECT_DEPENDENCY);
    }
}
