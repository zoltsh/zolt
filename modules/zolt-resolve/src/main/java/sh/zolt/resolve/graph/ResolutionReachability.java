package sh.zolt.resolve.graph;

import sh.zolt.dependency.DependencyScope;

/**
 * Path-aware evidence that a selected package was reached from an optional or required direct root.
 *
 * <p>The traverser records this before path-specific exclusion contexts are flattened into component
 * edges. A package is optional-only when it has at least one optional-root fact and no required-root
 * fact for the same selected version, variant, and scope.
 */
public record ResolutionReachability(
        PackageNode node,
        DependencyScope scope,
        boolean optionalRoot) {
}
