package sh.zolt.build.nativeimage;

import sh.zolt.build.packaging.PackageResult;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.SpringBootLoaderArtifact;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class NativePackagePolicy {
    private NativePackagePolicy() {
    }

    public static ProjectConfig packageConfig(ProjectConfig config) {
        ProjectConfig packageConfig = config.packageSettings().mode() == PackageMode.UBER
                ? config
                : config.withPackageSettings(PackageSettings.defaults());
        return packageConfig.withBuildSettings(
                NativePackageInputSettings.withOutputRoot(
                        packageConfig.build(),
                        config.nativeSettings().output() + "/input"));
    }

    static Predicate<ResolvedClasspathPackage> classpathFilter(ProjectConfig config) {
        PackageMode mode = config.packageSettings().mode();
        if (!config.frameworkSettings().springBoot().nativeEnabled()
                || (mode != PackageMode.SPRING_BOOT && mode != PackageMode.SPRING_BOOT_WAR)) {
            return ignored -> true;
        }
        return dependency -> !implicitSpringBootLoader(dependency);
    }

    static List<Path> runtimeClasspath(PackageResult packageResult, List<Path> runtimeClasspath) {
        if (packageResult.mode() == PackageMode.UBER || runtimeClasspath == null) {
            return List.of();
        }
        return runtimeClasspath;
    }

    /** Applies the standalone identity-based exclusions to an already projected workspace runtime. */
    public static List<Path> runtimeClasspath(
            ProjectConfig config,
            PackageResult packageResult,
            List<Path> runtimeClasspath,
            List<ResolvedClasspathPackage> classpathPackages) {
        List<Path> selected = runtimeClasspath(packageResult, runtimeClasspath);
        Predicate<ResolvedClasspathPackage> included = classpathFilter(config);
        Set<Path> excluded = classpathPackages.stream()
                .filter(included.negate())
                .map(dependency -> normalized(dependency.resolvedPackage().jarPath()))
                .collect(Collectors.toUnmodifiableSet());
        return selected.stream()
                .filter(path -> !excluded.contains(normalized(path)))
                .toList();
    }

    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static boolean implicitSpringBootLoader(ResolvedClasspathPackage dependency) {
        var resolved = dependency.resolvedPackage();
        var identity = resolved.artifactIdentity();
        return dependency.scope() == DependencyScope.RUNTIME
                && !resolved.direct()
                && SpringBootLoaderArtifact.isDefaultLoader(
                        resolved.packageId(),
                        identity.extension(),
                        identity.classifier());
    }
}
