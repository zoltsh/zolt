package sh.zolt.build.packaging;

import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.ZoltLockfile;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PackageRuntimeJarSelector {
    public List<PackageRuntimeJar> runtimeJars(ZoltLockfile lockfile, Path cacheRoot) {
        return runtimeJars(packagedClasspathPackages(lockfile, cacheRoot));
    }

    public List<PackageRuntimeJar> runtimeJars(List<ResolvedClasspathPackage> classpathPackages) {
        Map<String, PackageRuntimeJar> runtimeJars = new LinkedHashMap<>();
        packagedClasspathPackages(classpathPackages).stream()
                .filter(dependency -> dependency.scope().entersMainRuntimeClasspath())
                .sorted(Comparator.comparing(PackageRuntimeJarSelector::classpathSortKey))
                .map(PackageRuntimeJarSelector::runtimeJar)
                .forEach(runtimeJar -> runtimeJars.putIfAbsent(runtimeJarKey(runtimeJar), runtimeJar));
        return List.copyOf(runtimeJars.values());
    }

    public List<PackageRuntimeJar> runtimeJarsWithoutProvidedDuplicates(List<ResolvedClasspathPackage> classpathPackages) {
        Set<String> providedArtifactVariants = providedArtifactVariants(classpathPackages);
        return runtimeJars(classpathPackages).stream()
                .filter(runtimeJar -> !providedArtifactVariants.contains(
                        runtimeJar.artifactIdentity().artifactVariantKey()))
                .toList();
    }

    public List<PackageRuntimeJar> providedJars(List<ResolvedClasspathPackage> classpathPackages) {
        Map<String, PackageRuntimeJar> providedJars = new LinkedHashMap<>();
        classpathPackages.stream()
                .filter(dependency -> dependency.scope() == DependencyScope.PROVIDED)
                .sorted(Comparator.comparing(PackageRuntimeJarSelector::classpathSortKey))
                .map(PackageRuntimeJarSelector::runtimeJar)
                .forEach(runtimeJar -> providedJars.putIfAbsent(runtimeJarKey(runtimeJar), runtimeJar));
        return List.copyOf(providedJars.values());
    }

    public List<ResolvedClasspathPackage> packagedClasspathPackages(ZoltLockfile lockfile, Path cacheRoot) {
        return packagedClasspathPackages(LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot));
    }

    public List<ResolvedClasspathPackage> allClasspathPackages(ZoltLockfile lockfile, Path cacheRoot) {
        return LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot);
    }

    private List<ResolvedClasspathPackage> packagedClasspathPackages(List<ResolvedClasspathPackage> classpathPackages) {
        return classpathPackages.stream()
                .filter(dependency -> dependency.scope().packagedByDefault())
                .toList();
    }

    private static Set<String> providedArtifactVariants(
            List<ResolvedClasspathPackage> classpathPackages) {
        return classpathPackages.stream()
                .filter(dependency -> dependency.scope() == DependencyScope.PROVIDED)
                .map(dependency -> dependency.resolvedPackage()
                        .artifactIdentity()
                        .artifactVariantKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static PackageRuntimeJar runtimeJar(ResolvedClasspathPackage dependency) {
        return new PackageRuntimeJar(
                dependency.resolvedPackage().packageId(),
                dependency.resolvedPackage().selectedVersion(),
                dependency.resolvedPackage().jarPath(),
                dependency.resolvedPackage().artifactIdentity());
    }

    private static String classpathSortKey(ResolvedClasspathPackage dependency) {
        return dependency.resolvedPackage().packageId()
                + ":"
                + dependency.resolvedPackage().selectedVersion()
                + ":"
                + dependency.resolvedPackage().artifactIdentity().canonicalKey()
                + ":"
                + dependency.scope();
    }

    private static String runtimeJarKey(PackageRuntimeJar runtimeJar) {
        return runtimeJar.artifactIdentity().canonicalKey() + ":" + runtimeJar.jarPath();
    }
}
