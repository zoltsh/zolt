package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.LocalId;

final class AuthoredDependencyConstraintsTest {
    @Test
    void retainsStrictSelectorsInDeterministicCoordinateOrder() {
        DependencyCoordinate netty = new DependencyCoordinate("io.netty:netty-handler");
        DependencyCoordinate spring = new DependencyCoordinate("org.springframework:spring-core");
        LinkedHashMap<DependencyCoordinate, AuthoredDependencyConstraint> source = new LinkedHashMap<>();
        source.put(spring, constraint(new DependencyConstraintSelector.VersionReference(new LocalId("spring"))));
        source.put(netty, constraint(new DependencyConstraintSelector.FixedVersion("4.1.119.Final")));

        AuthoredDependencyConstraints constraints = new AuthoredDependencyConstraints(source);
        source.clear();

        assertEquals(List.of(netty, spring), List.copyOf(constraints.entries().keySet()));
        assertInstanceOf(
                DependencyConstraintSelector.FixedVersion.class,
                constraints.entries().get(netty).selector());
        assertInstanceOf(
                DependencyConstraintSelector.VersionReference.class,
                constraints.entries().get(spring).selector());
        assertThrows(UnsupportedOperationException.class, () -> constraints.entries().clear());
        assertEquals(Map.of(), AuthoredDependencyConstraints.empty().entries());
    }

    @Test
    void fixedConstraintsAcceptDeferredSnapshotsButRejectNonfixedSyntax() {
        assertEquals(
                "4.2-SNAPSHOT",
                new DependencyConstraintSelector.FixedVersion("4.2-SNAPSHOT").value());

        for (String value : List.of("", "[4.1,5.0)", "4.+", "LATEST", "${netty}", "4.1.")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new DependencyConstraintSelector.FixedVersion(value),
                    value);
        }
    }

    @Test
    void validatesVersionReferencesAndOptionalReasonsAtTheSourceBoundary() {
        AuthoredDependencyConstraint constraint = new AuthoredDependencyConstraint(
                new DependencyConstraintSelector.VersionReference(new LocalId("security-baseline")),
                Optional.of("Security baseline"));

        assertEquals("security-baseline",
                ((DependencyConstraintSelector.VersionReference) constraint.selector()).alias().value());
        assertEquals("Security baseline", constraint.reason().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new AuthoredDependencyConstraint(
                new DependencyConstraintSelector.FixedVersion("1.0.0"), Optional.of(" ")));
        assertThrows(NullPointerException.class, () -> new AuthoredDependencyConstraint(
                new DependencyConstraintSelector.FixedVersion("1.0.0"), null));
    }

    private static AuthoredDependencyConstraint constraint(DependencyConstraintSelector selector) {
        return new AuthoredDependencyConstraint(selector, Optional.empty());
    }
}
