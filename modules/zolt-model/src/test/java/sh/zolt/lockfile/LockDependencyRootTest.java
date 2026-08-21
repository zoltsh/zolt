package sh.zolt.lockfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;

final class LockDependencyRootTest {
    private static final PackageId PACKAGE = new PackageId("com.example", "client");
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
    void normalizesPortableMemberAndDefaultVariant() {
        LockDependencyRoot root = new LockDependencyRoot(
                "modules/cafe\u0301",
                PACKAGE,
                "1.4.0",
                null,
                DependencyLane.IMPLEMENTATION,
                Optional.of(DependencyScope.COMPILE),
                false,
                false);

        assertEquals("modules/caf\u00e9", root.member());
        assertEquals(LockArtifactVariant.defaultVariant(), root.variant());
        assertEquals(root, new LockDependencyRoot(
                "modules/caf\u00e9",
                PACKAGE,
                "1.4.0",
                LockArtifactVariant.defaultVariant(),
                DependencyLane.IMPLEMENTATION,
                Optional.of(DependencyScope.COMPILE),
                false,
                false));
    }

    @Test
    void retainsLaneIndependentlyFromSharedResolvedScope() {
        LockDependencyRoot api = resolved(DependencyLane.API, DependencyScope.COMPILE, false);
        LockDependencyRoot implementation = resolved(
                DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE, false);

        assertEquals(api.resolvedScope(), implementation.resolvedScope());
        assertNotEquals(api.lane(), implementation.lane());
        assertNotEquals(api, implementation);
    }

    @Test
    void publishOnlyRequiresAnAbsentResolvedScope() {
        LockDependencyRoot root = new LockDependencyRoot(
                ".", PACKAGE, "1.4.0", null, DependencyLane.API, null, true, true);

        assertEquals(Optional.empty(), root.resolvedScope());
        assertThrows(
                IllegalArgumentException.class,
                () -> resolved(DependencyLane.API, DependencyScope.COMPILE, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LockDependencyRoot(
                        ".", PACKAGE, "1.4.0", null, DependencyLane.API, null, false, false));
    }

    @Test
    void optionalAndPublishOnlyMetadataAreRestrictedToMeaningfulLanes() {
        for (DependencyLane lane : DependencyLane.values()) {
            if (OPTIONAL_LANES.contains(lane)) {
                resolved(lane, scope(lane), false, true);
            } else {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> resolved(lane, scope(lane), false, true));
            }

            if (PUBLISH_ONLY_LANES.contains(lane)) {
                publishOnly(lane);
            } else {
                assertThrows(IllegalArgumentException.class, () -> publishOnly(lane));
            }
        }
    }

    @Test
    void rejectsInvalidRequiredIdentity() {
        assertThrows(
                NullPointerException.class,
                () -> new LockDependencyRoot(
                        null, PACKAGE, "1.4.0", null, DependencyLane.API,
                        Optional.of(DependencyScope.COMPILE), false, false));
        assertThrows(NullPointerException.class, () -> resolved(null, DependencyScope.COMPILE, false));
        assertThrows(
                NullPointerException.class,
                () -> new LockDependencyRoot(
                        ".", null, "1.4.0", null, DependencyLane.API,
                        Optional.of(DependencyScope.COMPILE), false, false));
        assertThrows(
                NullPointerException.class,
                () -> new LockDependencyRoot(
                        ".", PACKAGE, null, null, DependencyLane.API,
                        Optional.of(DependencyScope.COMPILE), false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LockDependencyRoot(
                        ".", PACKAGE, " ", null, DependencyLane.API,
                        Optional.of(DependencyScope.COMPILE), false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LockDependencyRoot(
                        "modules/../client", PACKAGE, "1.4.0", null, DependencyLane.API,
                        Optional.of(DependencyScope.COMPILE), false, false));
    }

    private static LockDependencyRoot resolved(
            DependencyLane lane,
            DependencyScope scope,
            boolean publishOnly) {
        return resolved(lane, scope, publishOnly, false);
    }

    private static LockDependencyRoot resolved(
            DependencyLane lane,
            DependencyScope scope,
            boolean publishOnly,
            boolean optional) {
        return new LockDependencyRoot(
                ".", PACKAGE, "1.4.0", null, lane, Optional.of(scope), optional, publishOnly);
    }

    private static LockDependencyRoot publishOnly(DependencyLane lane) {
        return new LockDependencyRoot(
                ".", PACKAGE, "1.4.0", null, lane, Optional.empty(), false, true);
    }

    private static DependencyScope scope(DependencyLane lane) {
        return switch (lane) {
            case API, IMPLEMENTATION -> DependencyScope.COMPILE;
            case RUNTIME -> DependencyScope.RUNTIME;
            case PROVIDED -> DependencyScope.PROVIDED;
            case DEV -> DependencyScope.DEV;
            case TEST -> DependencyScope.TEST;
            case PROCESSOR -> DependencyScope.PROCESSOR;
            case TEST_PROCESSOR -> DependencyScope.TEST_PROCESSOR;
        };
    }
}
