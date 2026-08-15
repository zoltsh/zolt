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
import java.util.function.Predicate;

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
