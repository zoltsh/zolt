package sh.zolt.manifest;

import java.util.Map;

/** Immutable authored strict constraints in deterministic coordinate order. */
public record AuthoredDependencyConstraints(
        Map<DependencyCoordinate, AuthoredDependencyConstraint> entries) {
    public AuthoredDependencyConstraints {
        entries = ManifestModelValues.immutableSortedMap(
                entries,
                DependencyCoordinate::compareTo,
                "Dependency constraint coordinate",
                "Dependency constraint");
    }

    public static AuthoredDependencyConstraints empty() {
        return new AuthoredDependencyConstraints(Map.of());
    }
}
