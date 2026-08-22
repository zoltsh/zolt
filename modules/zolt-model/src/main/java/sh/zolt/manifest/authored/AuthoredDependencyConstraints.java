package sh.zolt.manifest.authored;

import java.util.Map;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.ManifestModelValues;

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
