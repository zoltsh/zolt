package sh.zolt.build.nativeimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class NativePackagePolicyTest {
    @Test
    void springBootNativeExcludesTheImplicitArchiveLoader() {
        var filter = NativePackagePolicy.classpathFilter(springBootNativeConfig());

        assertFalse(filter.test(dependency(
                "org.springframework.boot",
                "spring-boot-loader",
                false,
                DependencyScope.RUNTIME)));
        assertTrue(filter.test(dependency(
                "org.springframework.boot",
                "spring-boot",
                false,
                DependencyScope.TOOL_SPRING_AOT)));
        assertTrue(filter.test(dependency(
                "com.example",
                "runtime-lib",
                false,
                DependencyScope.RUNTIME)));
    }

    @Test
    void springBootNativeKeepsAnExplicitLoaderDependency() {
        var filter = NativePackagePolicy.classpathFilter(springBootNativeConfig());

        assertTrue(filter.test(dependency(
                "org.springframework.boot",
                "spring-boot-loader",
                true,
                DependencyScope.COMPILE)));
    }

    @Test
    void thinSpringNativeKeepsAnIndirectLoaderDependency() {
        var filter = NativePackagePolicy.classpathFilter(springNativeConfig("thin"));

        assertTrue(filter.test(dependency(
                "org.springframework.boot",
                "spring-boot-loader",
                false,
                DependencyScope.RUNTIME)));
    }

    @Test
    void workspaceRuntimeFilteringDropsOnlyTheImplicitLoaderByPackageIdentity() {
        var implicitLoader = dependency(
                "org.springframework.boot",
                "spring-boot-loader",
                false,
                DependencyScope.RUNTIME);
        var declaredLoader = dependency(
                "org.springframework.boot",
                "spring-boot-loader",
                true,
                DependencyScope.COMPILE);
        Path application = Path.of("demo.jar");
        var packageResult = new sh.zolt.build.packaging.PackageResult(
                null,
                sh.zolt.project.PackageMode.SPRING_BOOT,
                application,
                java.util.Optional.empty(),
                1,
                true);

        assertEquals(
                List.of(),
                NativePackagePolicy.runtimeClasspath(
                        springBootNativeConfig(),
                        packageResult,
                        List.of(implicitLoader.resolvedPackage().jarPath()),
                        List.of(implicitLoader)));
        assertEquals(
                List.of(declaredLoader.resolvedPackage().jarPath()),
                NativePackagePolicy.runtimeClasspath(
                        springBootNativeConfig(),
                        packageResult,
                        List.of(declaredLoader.resolvedPackage().jarPath()),
                        List.of(declaredLoader)));
    }

    private static ResolvedClasspathPackage dependency(
            String group,
            String artifact,
            boolean direct,
            DependencyScope scope) {
        PackageId packageId = new PackageId(group, artifact);
        return new ResolvedClasspathPackage(
                new ResolvedPackage(
                        packageId,
                        "3.3.6",
                        direct,
                        Path.of(artifact + ".pom"),
                        Path.of(artifact + ".jar")),
                scope);
    }

    private static ProjectConfig springBootNativeConfig() {
        return springNativeConfig("spring-boot");
    }

    private static ProjectConfig springNativeConfig(String packageMode) {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                main = "com.example.Main"

                [package]
                mode = "%s"

                [framework.springBoot.native]
                enabled = true
                """.formatted(packageMode));
    }
}
