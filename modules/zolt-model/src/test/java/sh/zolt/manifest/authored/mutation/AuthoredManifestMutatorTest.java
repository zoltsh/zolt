package sh.zolt.manifest.authored.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredProject;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredProjectMetadata;
import sh.zolt.manifest.authored.AuthoredToolchains;

final class AuthoredManifestMutatorTest {
    private static final List<DependencyLane> FINAL_LANE_ORDER = List.of(
            DependencyLane.IMPLEMENTATION,
            DependencyLane.API,
            DependencyLane.RUNTIME,
            DependencyLane.PROVIDED,
            DependencyLane.DEV,
            DependencyLane.TEST,
            DependencyLane.PROCESSOR,
            DependencyLane.TEST_PROCESSOR);

    @Test
    void setsReplacesAndRemovesVersionAliasesWithoutLosingPresence() {
        AuthoredManifest original = manifest();
        LocalId library = new LocalId("library");
        VersionAliasValue first = new VersionAliasValue("1.0.0");

        AuthoredManifest added = AuthoredManifestMutator.setVersionAlias(
                original, library, first);
        AuthoredManifest unchanged = AuthoredManifestMutator.setVersionAlias(
                added, library, first);
        AuthoredManifest replaced = AuthoredManifestMutator.setVersionAlias(
                added, library, new VersionAliasValue("2.0.0"));
        AuthoredManifest removed = AuthoredManifestMutator.removeVersionAlias(
                replaced, library);

        assertEquals(first, added.versions().orElseThrow().entries().get(library));
        assertSame(added, unchanged);
        assertEquals("2.0.0", replaced.versions().orElseThrow()
                .entries().get(library).value());
        assertTrue(removed.versions().isPresent());
        assertTrue(removed.versions().orElseThrow().entries().isEmpty());
        assertThrows(UnsupportedOperationException.class, () ->
                added.versions().orElseThrow().entries().clear());
        assertSame(removed, AuthoredManifestMutator.removeVersionAlias(removed, library));
    }

    @Test
    void mutatesPlatformsAndConstraintsWithTypedValues() {
        AuthoredManifest original = manifest();
        DependencyCoordinate platform = coordinate("org.example:platform");
        DependencyCoordinate dependency = coordinate("org.example:library");
        PlatformSelector platformSelector = new PlatformSelector.FixedVersion("1.0.0");
        AuthoredDependencyConstraint constraint = new AuthoredDependencyConstraint(
                new DependencyConstraintSelector.FixedVersion("1.0.0"),
                Optional.of("compatibility floor"));

        AuthoredManifest updated = AuthoredManifestMutator.setDependencyConstraint(
                AuthoredManifestMutator.setPlatform(
                        original, platform, platformSelector),
                dependency,
                constraint);

        assertEquals(platformSelector,
                updated.platforms().orElseThrow().entries().get(platform));
        assertEquals(constraint, updated.dependencyConstraints().orElseThrow()
                .entries().get(dependency));

        AuthoredManifest removed = AuthoredManifestMutator.removeDependencyConstraint(
                AuthoredManifestMutator.removePlatform(updated, platform), dependency);
        assertTrue(removed.platforms().orElseThrow().entries().isEmpty());
        assertTrue(removed.dependencyConstraints().orElseThrow().entries().isEmpty());
        assertSame(removed, AuthoredManifestMutator.removePlatform(removed, platform));
        assertSame(removed,
                AuthoredManifestMutator.removeDependencyConstraint(removed, dependency));
    }

    @Test
    void createsAndMutatesBothBomEntryCollections() {
        DependencyCoordinate library = coordinate("org.example:library");
        DependencyCoordinate imported = coordinate("org.example:platform-bom");
        AuthoredBom.Version version = new AuthoredBom.Version(
                new PlatformSelector.FixedVersion("1.0.0"),
                Optional.of("tests"),
                Optional.of("jar"));
        PlatformSelector selector = new PlatformSelector.FixedVersion("2.0.0");

        AuthoredManifest updated = AuthoredManifestMutator.setBomImport(
                AuthoredManifestMutator.setBomVersion(manifest(), library, version),
                imported,
                selector);
        AuthoredBom bom = updated.packaging().bom().orElseThrow();

        assertEquals(version, bom.versions().orElseThrow().get(library));
        assertEquals(selector, bom.imports().orElseThrow().get(imported));

        AuthoredManifest removed = AuthoredManifestMutator.removeBomImport(
                AuthoredManifestMutator.removeBomVersion(updated, library), imported);
        AuthoredBom empty = removed.packaging().bom().orElseThrow();
        assertTrue(empty.versions().isPresent());
        assertTrue(empty.versions().orElseThrow().isEmpty());
        assertTrue(empty.imports().isPresent());
        assertTrue(empty.imports().orElseThrow().isEmpty());
        assertSame(removed, AuthoredManifestMutator.removeBomVersion(removed, library));
        assertSame(removed, AuthoredManifestMutator.removeBomImport(removed, imported));
    }

    @Test
    void insertsAllEightDependencyLanesInFinalManifestOrder() {
        AuthoredManifest updated = manifest();
        int index = 0;
        for (DependencyLane lane : DependencyLane.values()) {
            updated = AuthoredManifestMutator.setDependency(
                    updated, dependency(lane, "org.example:library-" + index++));
        }

        assertEquals(
                FINAL_LANE_ORDER,
                updated.dependencies().orElseThrow().declarations().stream()
                        .map(AuthoredDependency::lane)
                        .toList());
    }

    @Test
    void replacesOneLaneCoordinateAndLeavesOtherLanesIndependent() {
        DependencyCoordinate coordinate = coordinate("org.example:library");
        AuthoredDependency implementation = dependency(
                DependencyLane.IMPLEMENTATION, coordinate, "1.0.0", metadata());
        AuthoredDependency processor = dependency(
                DependencyLane.PROCESSOR, coordinate, "1.0.0", metadata());
        AuthoredDependency replacement = dependency(
                DependencyLane.IMPLEMENTATION, coordinate, "2.0.0", metadata());

        AuthoredManifest updated = AuthoredManifestMutator.setDependency(
                AuthoredManifestMutator.setDependency(
                        AuthoredManifestMutator.setDependency(
                                manifest(), implementation),
                        processor),
                replacement);

        assertEquals(List.of(replacement, processor),
                updated.dependencies().orElseThrow().declarations());
        assertSame(updated, AuthoredManifestMutator.setDependency(updated, replacement));
    }

    @Test
    void allowsIndependentOrdinaryLaneEntriesOnlyWhenModelVariantsDiffer() {
        DependencyCoordinate coordinate = coordinate("org.example:library");
        AuthoredDependency plain = dependency(
                DependencyLane.IMPLEMENTATION, coordinate, "1.0.0", metadata());
        AuthoredDependency classified = dependency(
                DependencyLane.API,
                coordinate,
                "1.0.0",
                metadata(Optional.of("tests"), Optional.empty()));
        AuthoredManifest withPlain = AuthoredManifestMutator.setDependency(
                manifest(), plain);

        AuthoredManifest distinct = AuthoredManifestMutator.setDependency(
                withPlain, classified);
        assertEquals(2, distinct.dependencies().orElseThrow().declarations().size());

        AuthoredDependency sameVariant = dependency(
                DependencyLane.API, coordinate, "2.0.0", metadata());
        assertThrows(IllegalArgumentException.class, () ->
                AuthoredManifestMutator.setDependency(withPlain, sameVariant));
    }

    @Test
    void removesAnExactLaneCoordinateAndRetainsAnExplicitEmptyDomain() {
        DependencyCoordinate coordinate = coordinate("org.example:library");
        AuthoredManifest original = AuthoredManifestMutator.setDependency(
                manifest(), dependency(DependencyLane.TEST, coordinate, "1.0.0", metadata()));

        AuthoredManifest removed = AuthoredManifestMutator.removeDependency(
                original, DependencyLane.TEST, coordinate);

        assertTrue(removed.dependencies().isPresent());
        assertTrue(removed.dependencies().orElseThrow().declarations().isEmpty());
        assertSame(removed, AuthoredManifestMutator.removeDependency(
                removed, DependencyLane.TEST, coordinate));
    }

    @Test
    void movesOneExactCoordinateAndAppendsItToTheTargetLane() {
        AuthoredDependency implementation = dependency(
                DependencyLane.IMPLEMENTATION, "org.example:implementation");
        AuthoredDependency source = dependency(
                DependencyLane.API, "org.example:moved");
        AuthoredDependency targetExisting = dependency(
                DependencyLane.RUNTIME, "org.example:existing");
        AuthoredManifest original = manifestWithDependencies(
                List.of(implementation, source, targetExisting));

        AuthoredManifest moved = AuthoredManifestMutator.moveDependency(
                original,
                DependencyLane.API,
                DependencyLane.RUNTIME,
                source.coordinate());

        assertEquals(List.of(
                implementation,
                targetExisting,
                new AuthoredDependency(
                        DependencyLane.RUNTIME,
                        source.coordinate(),
                        source.selector(),
                        source.metadata())),
                moved.dependencies().orElseThrow().declarations());
    }

    @Test
    void rejectsInvalidOrAmbiguousDependencyMoves() {
        AuthoredDependency source = dependency(
                DependencyLane.IMPLEMENTATION, "org.example:library");
        AuthoredDependency occupied = dependency(
                DependencyLane.PROCESSOR, "org.example:library");
        AuthoredManifest original = manifestWithDependencies(List.of(source, occupied));

        assertEquals(
                "Dependency move lanes must differ.",
                assertThrows(IllegalArgumentException.class, () ->
                        AuthoredManifestMutator.moveDependency(
                                original,
                                DependencyLane.IMPLEMENTATION,
                                DependencyLane.IMPLEMENTATION,
                                source.coordinate())).getMessage());
        assertEquals(
                "Dependency `org.example:missing` is not declared in the IMPLEMENTATION lane.",
                assertThrows(IllegalArgumentException.class, () ->
                        AuthoredManifestMutator.moveDependency(
                                original,
                                DependencyLane.IMPLEMENTATION,
                                DependencyLane.RUNTIME,
                                coordinate("org.example:missing"))).getMessage());
        assertEquals(
                "Dependency `org.example:library` is already declared in the PROCESSOR lane.",
                assertThrows(IllegalArgumentException.class, () ->
                        AuthoredManifestMutator.moveDependency(
                                original,
                                DependencyLane.IMPLEMENTATION,
                                DependencyLane.PROCESSOR,
                                source.coordinate())).getMessage());
    }

    @Test
    void failsClosedOnSameLaneCoordinateAmbiguityInConstructedModels() {
        DependencyCoordinate coordinate = coordinate("org.example:library");
        AuthoredDependency plain = dependency(
                DependencyLane.IMPLEMENTATION, coordinate, "1.0.0", metadata());
        AuthoredDependency classified = dependency(
                DependencyLane.IMPLEMENTATION,
                coordinate,
                "1.0.0",
                metadata(Optional.of("tests"), Optional.empty()));
        AuthoredManifest ambiguous = manifestWithDependencies(List.of(plain, classified));

        String message = "Dependency coordinate `org.example:library` is ambiguous in the "
                + "IMPLEMENTATION lane.";
        assertEquals(message, assertThrows(IllegalArgumentException.class, () ->
                AuthoredManifestMutator.setDependency(ambiguous, plain)).getMessage());
        assertEquals(message, assertThrows(IllegalArgumentException.class, () ->
                AuthoredManifestMutator.removeDependency(
                        ambiguous, DependencyLane.IMPLEMENTATION, coordinate)).getMessage());
        assertEquals(message, assertThrows(IllegalArgumentException.class, () ->
                AuthoredManifestMutator.moveDependency(
                        ambiguous,
                        DependencyLane.IMPLEMENTATION,
                        DependencyLane.RUNTIME,
                        coordinate)).getMessage());
    }

    @Test
    void delegatesLaneMetadataAndBomConflictsToModelConstructors() {
        AuthoredDependency optional = new AuthoredDependency(
                DependencyLane.API,
                coordinate("org.example:optional"),
                new DependencySelector.FixedVersion("1.0.0"),
                new AuthoredDependencyMetadata(
                        true, false, Optional.empty(), Optional.empty(), List.of()));
        AuthoredManifest withDependency = AuthoredManifestMutator.setDependency(
                manifest(), optional);

        assertThrows(IllegalArgumentException.class, () ->
                AuthoredManifestMutator.moveDependency(
                        withDependency,
                        DependencyLane.API,
                        DependencyLane.TEST,
                        optional.coordinate()));
        assertThrows(IllegalArgumentException.class, () ->
                AuthoredManifestMutator.setBomVersion(
                        withDependency,
                        coordinate("org.example:bom-entry"),
                        new AuthoredBom.Version(
                                new PlatformSelector.FixedVersion("1.0.0"),
                                Optional.empty(),
                                Optional.empty())));
    }

    private static AuthoredManifest manifestWithDependencies(
            List<AuthoredDependency> dependencies) {
        AuthoredManifest source = manifest();
        return new AuthoredManifest(
                source.workspace(), source.project(), source.toolchains(),
                source.versions(), source.repositories(), source.credentials(), source.platforms(),
                Optional.of(new AuthoredDependencies(dependencies)),
                source.dependencyConstraints(), source.dependencyPolicy(),
                source.build(), source.generated(), source.packaging(),
                source.publishing(), source.commands());
    }

    private static AuthoredManifest manifest() {
        AuthoredProject project = new AuthoredProject(
                new AuthoredProjectIdentity(
                        new ProjectName("demo"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                AuthoredProjectMetadata.empty());
        return new AuthoredManifest(
                Optional.empty(), Optional.of(project), AuthoredToolchains.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                AuthoredBuildConfiguration.empty(), Optional.empty(), AuthoredPackaging.empty(),
                Optional.empty(), Optional.empty());
    }

    private static AuthoredDependency dependency(
            DependencyLane lane,
            String coordinate) {
        return dependency(lane, coordinate(coordinate), "1.0.0", metadata());
    }

    private static AuthoredDependency dependency(
            DependencyLane lane,
            DependencyCoordinate coordinate,
            String version,
            AuthoredDependencyMetadata metadata) {
        return new AuthoredDependency(
                lane,
                coordinate,
                new DependencySelector.FixedVersion(version),
                metadata);
    }

    private static AuthoredDependencyMetadata metadata() {
        return metadata(Optional.empty(), Optional.empty());
    }

    private static AuthoredDependencyMetadata metadata(
            Optional<String> classifier,
            Optional<String> type) {
        return new AuthoredDependencyMetadata(
                false, false, classifier, type, List.of());
    }

    private static DependencyCoordinate coordinate(String value) {
        return new DependencyCoordinate(value);
    }
}
