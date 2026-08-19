package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;

final class AuthoredDependenciesTest {
    @Test
    void preservesAllEightAuthoredLanesAndIsDeeplyImmutable() {
        ArrayList<AuthoredDependency> source = new ArrayList<>();
        int index = 0;
        for (DependencyLane lane : DependencyLane.values()) {
            source.add(dependency(lane, "com.example:artifact-" + index++, AuthoredDependencyMetadata.none()));
        }
        AuthoredDependencies dependencies = new AuthoredDependencies(source);
        source.clear();

        assertEquals(8, dependencies.declarations().size());
        for (DependencyLane lane : DependencyLane.values()) {
            assertEquals(lane, dependencies.inLane(lane).getFirst().lane());
            assertEquals(1, dependencies.byLane().get(lane).size());
        }
        assertThrows(UnsupportedOperationException.class, () -> dependencies.declarations().clear());
        assertThrows(UnsupportedOperationException.class, () -> dependencies.inLane(DependencyLane.API).clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> dependencies.byLane().put(DependencyLane.API, List.of()));
    }

    @Test
    void rejectsOneVariantAcrossAnyTwoOrdinaryLanes() {
        List<DependencyLane> ordinary = List.of(
                DependencyLane.API,
                DependencyLane.IMPLEMENTATION,
                DependencyLane.RUNTIME,
                DependencyLane.PROVIDED,
                DependencyLane.DEV,
                DependencyLane.TEST);
        for (int left = 0; left < ordinary.size(); left++) {
            for (int right = left + 1; right < ordinary.size(); right++) {
                AuthoredDependency first = dependency(
                        ordinary.get(left), "com.example:client", AuthoredDependencyMetadata.none());
                AuthoredDependency second = dependency(
                        ordinary.get(right), "com.example:client", AuthoredDependencyMetadata.none());
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new AuthoredDependencies(List.of(first, second)),
                        ordinary.get(left) + " and " + ordinary.get(right));
            }
        }
    }

    @Test
    void distinctClassifierOrTypeMayOccupyDifferentOrdinaryLanes() {
        AuthoredDependency plain =
                dependency(DependencyLane.IMPLEMENTATION, "com.example:client", AuthoredDependencyMetadata.none());
        AuthoredDependency tests = dependency(
                DependencyLane.TEST,
                "com.example:client",
                metadata(Optional.of("tests"), Optional.empty()));
        AuthoredDependency zip = dependency(
                DependencyLane.RUNTIME,
                "com.example:client",
                metadata(Optional.empty(), Optional.of("zip")));

        assertEquals(3, new AuthoredDependencies(List.of(plain, tests, zip)).declarations().size());
    }

    @Test
    void selectorVersionOptionalityAndExclusionsAreNotVariantIdentity() {
        DependencyCoordinate coordinate = new DependencyCoordinate("com.example:client");
        AuthoredDependency original = new AuthoredDependency(
                DependencyLane.API,
                coordinate,
                new DependencySelector.FixedVersion("1.0.0"),
                AuthoredDependencyMetadata.none());
        AuthoredDependency differentVersionAndMetadata = new AuthoredDependency(
                DependencyLane.IMPLEMENTATION,
                coordinate,
                new DependencySelector.FixedVersion("2.0.0"),
                new AuthoredDependencyMetadata(
                        true,
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        List.of(new DependencyCoordinate("legacy:logging"))));
        AuthoredDependency differentSelector = new AuthoredDependency(
                DependencyLane.RUNTIME,
                coordinate,
                new DependencySelector.VersionReference(new LocalId("client")),
                new AuthoredDependencyMetadata(
                        true,
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        List.of(new DependencyCoordinate("legacy:bridge"))));

        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredDependencies(List.of(original, differentVersionAndMetadata)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredDependencies(List.of(original, differentSelector)));
    }

    @Test
    void processorContextsMayReuseAnOrdinaryOrEachOthersVariant() {
        AuthoredDependency ordinary =
                dependency(DependencyLane.IMPLEMENTATION, "org.projectlombok:lombok", AuthoredDependencyMetadata.none());
        AuthoredDependency processor =
                dependency(DependencyLane.PROCESSOR, "org.projectlombok:lombok", AuthoredDependencyMetadata.none());
        AuthoredDependency testProcessor =
                dependency(DependencyLane.TEST_PROCESSOR, "org.projectlombok:lombok", AuthoredDependencyMetadata.none());

        assertEquals(
                3,
                new AuthoredDependencies(List.of(ordinary, processor, testProcessor)).declarations().size());
    }

    @Test
    void rejectsTheSameVariantTwiceInsideOneLaneIncludingToolLanes() {
        for (DependencyLane lane : DependencyLane.values()) {
            AuthoredDependency first = dependency(lane, "com.example:client", AuthoredDependencyMetadata.none());
            AuthoredDependency second = dependency(lane, "com.example:client", AuthoredDependencyMetadata.none());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AuthoredDependencies(List.of(first, second)),
                    lane.toString());
        }
    }

    private static AuthoredDependency dependency(
            DependencyLane lane,
            String coordinate,
            AuthoredDependencyMetadata metadata) {
        return new AuthoredDependency(
                lane,
                new DependencyCoordinate(coordinate),
                new DependencySelector.FixedVersion("1.0.0"),
                metadata);
    }

    private static AuthoredDependencyMetadata metadata(Optional<String> classifier, Optional<String> type) {
        return new AuthoredDependencyMetadata(false, false, classifier, type, List.of());
    }
}
