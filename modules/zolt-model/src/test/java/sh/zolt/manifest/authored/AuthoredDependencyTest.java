package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.LocalId;

final class AuthoredDependencyTest {
    private static final DependencyCoordinate COORDINATE = new DependencyCoordinate("com.example:client");
    private static final Set<DependencyLane> OPTIONAL_LANES = EnumSet.of(
            DependencyLane.API,
            DependencyLane.IMPLEMENTATION,
            DependencyLane.RUNTIME);
    private static final Set<DependencyLane> PUBLISH_ONLY_LANES = EnumSet.of(
            DependencyLane.API,
            DependencyLane.IMPLEMENTATION,
            DependencyLane.RUNTIME,
            DependencyLane.PROVIDED);

    @Test
    void everySelectorIsValidInEveryLaneWithoutMetadata() {
        for (DependencyLane lane : DependencyLane.values()) {
            for (DependencySelector selector : selectors()) {
                AuthoredDependency dependency = dependency(lane, selector, AuthoredDependencyMetadata.none());
                assertEquals(lane, dependency.lane());
                assertEquals(selector, dependency.selector());
            }
        }
    }

    @Test
    void optionalMetadataIsLimitedToPropagatingClasspathLanes() {
        AuthoredDependencyMetadata optional =
                new AuthoredDependencyMetadata(true, false, Optional.empty(), Optional.empty(), List.of());
        for (DependencyLane lane : DependencyLane.values()) {
            for (DependencySelector selector : selectors()) {
                if (OPTIONAL_LANES.contains(lane)) {
                    dependency(lane, selector, optional);
                } else {
                    assertThrows(IllegalArgumentException.class, () -> dependency(lane, selector, optional));
                }
            }
        }
    }

    @Test
    void publishOnlyRequiresAResolvablePublicationVersionAndPublishedLane() {
        AuthoredDependencyMetadata publishOnly =
                new AuthoredDependencyMetadata(false, true, Optional.empty(), Optional.empty(), List.of());
        for (DependencyLane lane : DependencyLane.values()) {
            for (DependencySelector selector : selectors()) {
                boolean allowed = PUBLISH_ONLY_LANES.contains(lane)
                        && (selector instanceof DependencySelector.FixedVersion
                                || selector instanceof DependencySelector.VersionReference);
                if (allowed) {
                    dependency(lane, selector, publishOnly);
                } else {
                    assertThrows(IllegalArgumentException.class, () -> dependency(lane, selector, publishOnly));
                }
            }
        }
    }

    @Test
    void optionalAndPublishOnlyRemainIndependentObservableFacts() {
        AuthoredDependencyMetadata metadata =
                new AuthoredDependencyMetadata(true, true, Optional.empty(), Optional.empty(), List.of());
        for (DependencySelector selector : List.of(
                new DependencySelector.FixedVersion("1.4.0"),
                new DependencySelector.VersionReference(new LocalId("client")))) {
            AuthoredDependency dependency = dependency(DependencyLane.API, selector, metadata);

            assertTrue(dependency.metadata().optional());
            assertTrue(dependency.metadata().publishOnly());
        }
    }

    @Test
    void externalArtifactMetadataWorksInAllLanesForExternalSelectors() {
        AuthoredDependencyMetadata externalMetadata = new AuthoredDependencyMetadata(
                false,
                false,
                Optional.of("tests"),
                Optional.of("test-jar"),
                List.of(new DependencyCoordinate("legacy:logging")));
        List<DependencySelector> externalSelectors = List.of(
                new DependencySelector.FixedVersion("1.4.0"),
                new DependencySelector.VersionReference(new LocalId("client")),
                new DependencySelector.Managed());

        for (DependencyLane lane : DependencyLane.values()) {
            for (DependencySelector selector : externalSelectors) {
                dependency(lane, selector, externalMetadata);
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> dependency(lane, new DependencySelector.Workspace(), externalMetadata));
        }
    }

    @Test
    void workspaceSelectorAllowsOnlyOptionalMetadataWhereItHasSemantics() {
        AuthoredDependencyMetadata optional =
                new AuthoredDependencyMetadata(true, false, Optional.empty(), Optional.empty(), List.of());

        for (DependencyLane lane : OPTIONAL_LANES) {
            dependency(lane, new DependencySelector.Workspace(), optional);
        }
    }

    private static List<DependencySelector> selectors() {
        return List.of(
                new DependencySelector.FixedVersion("1.4.0"),
                new DependencySelector.VersionReference(new LocalId("client")),
                new DependencySelector.Managed(),
                new DependencySelector.Workspace());
    }

    private static AuthoredDependency dependency(
            DependencyLane lane,
            DependencySelector selector,
            AuthoredDependencyMetadata metadata) {
        return new AuthoredDependency(lane, COORDINATE, selector, metadata);
    }
}
