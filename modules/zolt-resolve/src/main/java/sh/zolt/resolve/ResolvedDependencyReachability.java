package sh.zolt.resolve;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.resolve.graph.ResolutionGraph;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selected-package reachability after path-specific exclusions have been applied.
 *
 * <p>{@code optionalOnly} is true only when the package is reachable from at least one optional
 * direct root and from no required direct root in the same version, variant, and scope lane.
 */
public record ResolvedDependencyReachability(
        PackageId packageId,
        String version,
        LockArtifactVariant variant,
        DependencyScope scope,
        boolean optionalOnly) {
    static List<ResolvedDependencyReachability> from(
            ResolutionGraph graph) {
        Map<Key, Reachability> byPackage = new LinkedHashMap<>();
        graph.reachability().forEach(fact -> {
            Key key = new Key(
                    fact.node().packageId(),
                    fact.node().selectedVersion(),
                    fact.node().variant(),
                    fact.scope());
            Reachability reachability =
                    byPackage.computeIfAbsent(key, ignored -> new Reachability());
            if (fact.optionalRoot()) {
                reachability.optional = true;
            } else {
                reachability.required = true;
            }
        });
        return byPackage.entrySet().stream()
                .map(entry -> new ResolvedDependencyReachability(
                        entry.getKey().packageId(),
                        entry.getKey().version(),
                        entry.getKey().variant(),
                        entry.getKey().scope(),
                        entry.getValue().optional
                                && !entry.getValue().required))
                .sorted(Comparator
                        .comparing((ResolvedDependencyReachability fact) ->
                                fact.packageId().toString())
                        .thenComparing(ResolvedDependencyReachability::version)
                        .thenComparing(ResolvedDependencyReachability::variant)
                        .thenComparing(fact -> fact.scope().lockfileName()))
                .toList();
    }

    private record Key(
            PackageId packageId,
            String version,
            LockArtifactVariant variant,
            DependencyScope scope) {
    }

    private static final class Reachability {
        private boolean optional;
        private boolean required;
    }
}
