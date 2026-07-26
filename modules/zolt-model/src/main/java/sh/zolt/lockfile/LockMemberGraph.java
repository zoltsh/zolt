package sh.zolt.lockfile;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import java.util.List;

/**
 * One workspace member's graph and policy view of a collapsed external package identity.
 *
 * <p>{@code declaredOptional} preserves the direct declaration fact used by publication metadata.
 * {@code optionalOnly} records effective reachability after every direct root has been traversed.
 * A directly optional package can therefore have {@code declaredOptional=true} and
 * {@code optionalOnly=false} when another required root reaches the same identity.
 */
public record LockMemberGraph(
        String member,
        PackageId packageId,
        String version,
        LockArtifactVariant variant,
        DependencyScope scope,
        List<String> dependencies,
        List<String> policies,
        boolean declaredOptional,
        boolean optionalOnly) {
    public LockMemberGraph {
        variant = variant == null ? LockArtifactVariant.defaultVariant() : variant;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        policies = policies == null ? List.of() : List.copyOf(policies);
    }

    public LockMemberGraph(
            String member,
            PackageId packageId,
            String version,
            LockArtifactVariant variant,
            DependencyScope scope,
            List<String> dependencies,
            List<String> policies,
            boolean optionalOnly) {
        this(
                member,
                packageId,
                version,
                variant,
                scope,
                dependencies,
                policies,
                false,
                optionalOnly);
    }

    public LockMemberGraph(
            String member,
            PackageId packageId,
            String version,
            LockArtifactVariant variant,
            DependencyScope scope,
            List<String> dependencies,
            List<String> policies) {
        this(
                member,
                packageId,
                version,
                variant,
                scope,
                dependencies,
                policies,
                false,
                false);
    }

    public boolean describes(LockPackage lockPackage) {
        return packageId.equals(lockPackage.packageId())
                && version.equals(lockPackage.version())
                && variant.equals(LockArtifactVariant.of(lockPackage))
                && scope == lockPackage.scope();
    }
}
