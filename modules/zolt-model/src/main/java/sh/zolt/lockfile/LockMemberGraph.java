package sh.zolt.lockfile;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import java.util.List;

/**
 * One workspace member's graph and policy view of a collapsed external package identity.
 */
public record LockMemberGraph(
        String member,
        PackageId packageId,
        String version,
        LockArtifactVariant variant,
        DependencyScope scope,
        List<String> dependencies,
        List<String> policies,
        boolean optional) {
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
            List<String> policies) {
        this(
                member,
                packageId,
                version,
                variant,
                scope,
                dependencies,
                policies,
                false);
    }

    public boolean describes(LockPackage lockPackage) {
        return packageId.equals(lockPackage.packageId())
                && version.equals(lockPackage.version())
                && variant.equals(LockArtifactVariant.of(lockPackage))
                && scope == lockPackage.scope();
    }
}
