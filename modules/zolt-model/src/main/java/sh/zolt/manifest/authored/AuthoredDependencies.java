package sh.zolt.manifest.authored;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import sh.zolt.dependency.DependencyLane;

/** Immutable authored dependencies with lane and variant uniqueness retained. */
public record AuthoredDependencies(List<AuthoredDependency> declarations) {
    private static final Set<DependencyLane> ORDINARY_LANES = EnumSet.of(
            DependencyLane.API,
            DependencyLane.IMPLEMENTATION,
            DependencyLane.RUNTIME,
            DependencyLane.PROVIDED,
            DependencyLane.DEV,
            DependencyLane.TEST);

    public AuthoredDependencies {
        Objects.requireNonNull(declarations, "Authored dependencies must not be null.");
        validateUniqueness(declarations);
        declarations = List.copyOf(declarations);
    }

    public static AuthoredDependencies empty() {
        return new AuthoredDependencies(List.of());
    }

    public List<AuthoredDependency> inLane(DependencyLane lane) {
        Objects.requireNonNull(lane, "Dependency lane must not be null.");
        return declarations.stream().filter(dependency -> dependency.lane() == lane).toList();
    }

    public Map<DependencyLane, List<AuthoredDependency>> byLane() {
        EnumMap<DependencyLane, List<AuthoredDependency>> result = new EnumMap<>(DependencyLane.class);
        for (DependencyLane lane : DependencyLane.values()) {
            result.put(lane, inLane(lane));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void validateUniqueness(List<AuthoredDependency> declarations) {
        EnumMap<DependencyLane, Set<DependencyVariant>> seenByLane = new EnumMap<>(DependencyLane.class);
        Map<DependencyVariant, DependencyLane> ordinaryLaneByVariant = new HashMap<>();
        for (DependencyLane lane : DependencyLane.values()) {
            seenByLane.put(lane, new HashSet<>());
        }
        for (AuthoredDependency declaration : declarations) {
            Objects.requireNonNull(declaration, "Authored dependency must not be null.");
            DependencyVariant variant = declaration.variant();
            if (!seenByLane.get(declaration.lane()).add(variant)) {
                throw new IllegalArgumentException(
                        "Dependency variant `" + variant.key() + "` is declared more than once in the "
                                + declaration.lane() + " lane.");
            }
            if (ORDINARY_LANES.contains(declaration.lane())) {
                DependencyLane existing = ordinaryLaneByVariant.putIfAbsent(variant, declaration.lane());
                if (existing != null) {
                    throw new IllegalArgumentException(
                            "Dependency variant `" + variant.key() + "` cannot appear in both the "
                                    + existing + " and " + declaration.lane() + " ordinary lanes.");
                }
            }
        }
    }
}
