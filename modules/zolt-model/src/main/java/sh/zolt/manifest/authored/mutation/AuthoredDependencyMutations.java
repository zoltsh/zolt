package sh.zolt.manifest.authored.mutation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;

/** Exact (lane, coordinate) mutations for parser-addressable dependency declarations. */
final class AuthoredDependencyMutations {
    private AuthoredDependencyMutations() {
    }

    static Optional<AuthoredDependencies> set(
            Optional<AuthoredDependencies> domain,
            AuthoredDependency dependency) {
        Objects.requireNonNull(domain, "Authored dependencies must not be null.");
        Objects.requireNonNull(dependency, "Authored dependency is required.");
        ArrayList<AuthoredDependency> declarations = declarations(domain);
        int existing = indexOf(
                declarations, dependency.lane(), dependency.coordinate());
        if (existing >= 0) {
            if (declarations.get(existing).equals(dependency)) {
                return domain;
            }
            declarations.set(existing, dependency);
        } else {
            declarations.add(insertionIndex(declarations, dependency.lane()), dependency);
        }
        return Optional.of(new AuthoredDependencies(declarations));
    }

    static Optional<AuthoredDependencies> remove(
            Optional<AuthoredDependencies> domain,
            DependencyLane lane,
            DependencyCoordinate coordinate) {
        Objects.requireNonNull(domain, "Authored dependencies must not be null.");
        Objects.requireNonNull(lane, "Dependency lane is required.");
        Objects.requireNonNull(coordinate, "Dependency coordinate is required.");
        if (domain.isEmpty()) {
            return domain;
        }
        ArrayList<AuthoredDependency> declarations = declarations(domain);
        int existing = indexOf(declarations, lane, coordinate);
        if (existing < 0) {
            return domain;
        }
        declarations.remove(existing);
        return Optional.of(new AuthoredDependencies(declarations));
    }

    static Optional<AuthoredDependencies> move(
            Optional<AuthoredDependencies> domain,
            DependencyLane sourceLane,
            DependencyLane targetLane,
            DependencyCoordinate coordinate) {
        Objects.requireNonNull(domain, "Authored dependencies must not be null.");
        Objects.requireNonNull(sourceLane, "Source dependency lane is required.");
        Objects.requireNonNull(targetLane, "Target dependency lane is required.");
        Objects.requireNonNull(coordinate, "Dependency coordinate is required.");
        if (sourceLane == targetLane) {
            throw new IllegalArgumentException("Dependency move lanes must differ.");
        }
        ArrayList<AuthoredDependency> declarations = declarations(domain);
        int source = indexOf(declarations, sourceLane, coordinate);
        if (source < 0) {
            throw new IllegalArgumentException(
                    "Dependency `" + coordinate + "` is not declared in the "
                            + sourceLane + " lane.");
        }
        if (indexOf(declarations, targetLane, coordinate) >= 0) {
            throw new IllegalArgumentException(
                    "Dependency `" + coordinate + "` is already declared in the "
                            + targetLane + " lane.");
        }
        AuthoredDependency existing = declarations.remove(source);
        AuthoredDependency moved = new AuthoredDependency(
                targetLane,
                existing.coordinate(),
                existing.selector(),
                existing.metadata());
        declarations.add(insertionIndex(declarations, targetLane), moved);
        return Optional.of(new AuthoredDependencies(declarations));
    }

    private static ArrayList<AuthoredDependency> declarations(
            Optional<AuthoredDependencies> domain) {
        List<AuthoredDependency> values = domain
                .map(AuthoredDependencies::declarations)
                .orElseGet(List::of);
        validateAddressable(values);
        return new ArrayList<>(values);
    }

    private static void validateAddressable(List<AuthoredDependency> declarations) {
        EnumMap<DependencyLane, Set<DependencyCoordinate>> seen =
                new EnumMap<>(DependencyLane.class);
        for (DependencyLane lane : DependencyLane.values()) {
            seen.put(lane, new HashSet<>());
        }
        int previousRank = -1;
        for (AuthoredDependency dependency : declarations) {
            int rank = dependency.lane().canonicalOrder();
            if (rank < previousRank) {
                throw new IllegalArgumentException(
                        "Authored dependencies are not in final manifest lane order.");
            }
            previousRank = rank;
            if (!seen.get(dependency.lane()).add(dependency.coordinate())) {
                throw new IllegalArgumentException(
                        "Dependency coordinate `" + dependency.coordinate()
                                + "` is ambiguous in the " + dependency.lane() + " lane.");
            }
        }
    }

    private static int indexOf(
            List<AuthoredDependency> declarations,
            DependencyLane lane,
            DependencyCoordinate coordinate) {
        for (int index = 0; index < declarations.size(); index++) {
            AuthoredDependency dependency = declarations.get(index);
            if (dependency.lane() == lane && dependency.coordinate().equals(coordinate)) {
                return index;
            }
        }
        return -1;
    }

    private static int insertionIndex(
            List<AuthoredDependency> declarations,
            DependencyLane lane) {
        int rank = lane.canonicalOrder();
        int insertion = 0;
        for (int index = 0; index < declarations.size(); index++) {
            int candidate = declarations.get(index).lane().canonicalOrder();
            if (candidate <= rank) {
                insertion = index + 1;
            } else {
                break;
            }
        }
        return insertion;
    }
}
