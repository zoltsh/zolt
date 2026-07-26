package sh.zolt.build.packageauthority;

import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.SpringBootLoaderArtifact;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.PackageMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Direct provided declarations that authoritatively override deployable runtime artifacts.
 *
 * <p>Transitive provided reachability is not a declaration and therefore cannot suppress an
 * independently required runtime path. Spring Boot's default loader remains mandatory tooling in
 * executable Spring Boot layouts even when the same variant is directly provided.
 */
public final class ProvidedPackagingOverrides {
    private final Set<String> artifactVariants;

    private ProvidedPackagingOverrides(Set<String> artifactVariants) {
        this.artifactVariants = Set.copyOf(artifactVariants);
    }

    public static ProvidedPackagingOverrides fromLockfile(ZoltLockfile lockfile) {
        return fromLockPackages(lockfile.packages());
    }

    public static ProvidedPackagingOverrides fromLockPackages(
            List<LockPackage> packages) {
        Set<String> variants = new LinkedHashSet<>();
        packages.stream()
                .filter(LockPackage::direct)
                .filter(lockPackage ->
                        lockPackage.scope() == DependencyScope.PROVIDED)
                .map(NestedArtifactIdentity::of)
                .map(NestedArtifactIdentity::artifactVariantKey)
                .forEach(variants::add);
        return new ProvidedPackagingOverrides(variants);
    }

    public static ProvidedPackagingOverrides fromClasspathPackages(
            List<ResolvedClasspathPackage> packages) {
        Set<String> variants = new LinkedHashSet<>();
        packages.stream()
                .filter(packageEntry ->
                        packageEntry.resolvedPackage().direct())
                .filter(packageEntry ->
                        packageEntry.scope() == DependencyScope.PROVIDED)
                .map(packageEntry ->
                        packageEntry.resolvedPackage().artifactIdentity())
                .map(NestedArtifactIdentity::artifactVariantKey)
                .forEach(variants::add);
        return new ProvidedPackagingOverrides(variants);
    }

    public boolean suppresses(
            NestedArtifactIdentity identity,
            PackageMode mode) {
        if (requiresDefaultSpringBootLoader(mode)
                && SpringBootLoaderArtifact.isDefaultLoader(
                        identity.packageId(),
                        identity.extension(),
                        identity.classifier())) {
            return false;
        }
        return artifactVariants.contains(identity.artifactVariantKey());
    }

    private static boolean requiresDefaultSpringBootLoader(PackageMode mode) {
        return mode == PackageMode.SPRING_BOOT
                || mode == PackageMode.SPRING_BOOT_WAR;
    }
}
