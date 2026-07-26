package sh.zolt.build.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.NestedArtifactIdentity.SourceKind;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PackageRuntimeJarSelectorTest {
    private final PackageRuntimeJarSelector selector = new PackageRuntimeJarSelector();

    @Test
    void runtimeJarsAreDeterministicAndDeduplicatedByPackageVersionAndJar() {
        Path betaJar = Path.of("cache/com/example/beta/1.0.0/beta-1.0.0.jar");
        Path alphaJar = Path.of("cache/com/example/alpha/1.0.0/alpha-1.0.0.jar");

        List<PackageRuntimeJar> result = selector.runtimeJars(List.of(
                dependency("com.example", "beta", "1.0.0", DependencyScope.RUNTIME, betaJar),
                dependency("com.example", "alpha", "1.0.0", DependencyScope.RUNTIME, alphaJar),
                dependency("com.example", "alpha", "1.0.0", DependencyScope.COMPILE, alphaJar),
                dependency("com.example", "provided", "1.0.0", DependencyScope.PROVIDED, Path.of("provided.jar")),
                dependency("com.example", "dev", "1.0.0", DependencyScope.DEV, Path.of("dev.jar"))));

        assertEquals(List.of(
                        new PackageRuntimeJar(new PackageId("com.example", "alpha"), "1.0.0", alphaJar),
                        new PackageRuntimeJar(new PackageId("com.example", "beta"), "1.0.0", betaJar)),
                result);
    }

    @Test
    void runtimeJarsWithoutProvidedDuplicatesExcludeProvidedPackageIds() {
        Path runtimeJar = Path.of("cache/com/example/shared/1.0.0/shared-1.0.0.jar");
        Path providedJar = Path.of("cache/com/example/shared/1.0.0/shared-provided-1.0.0.jar");

        List<PackageRuntimeJar> result = selector.runtimeJarsWithoutProvidedDuplicates(List.of(
                dependency("com.example", "shared", "1.0.0", DependencyScope.RUNTIME, runtimeJar),
                dependency("com.example", "shared", "1.0.0", DependencyScope.PROVIDED, providedJar),
                dependency("com.example", "runtime-only", "1.0.0", DependencyScope.RUNTIME, Path.of("runtime.jar"))));

        assertEquals(List.of(
                        new PackageRuntimeJar(new PackageId("com.example", "runtime-only"), "1.0.0", Path.of("runtime.jar"))),
                result);
    }

    @Test
    void providedJarsSelectOnlyProvidedScope() {
        Path providedJar = Path.of("cache/com/example/provided/1.0.0/provided-1.0.0.jar");

        List<PackageRuntimeJar> result = selector.providedJars(List.of(
                dependency("com.example", "runtime", "1.0.0", DependencyScope.RUNTIME, Path.of("runtime.jar")),
                dependency("com.example", "provided", "1.0.0", DependencyScope.PROVIDED, providedJar)));

        assertEquals(List.of(
                        new PackageRuntimeJar(new PackageId("com.example", "provided"), "1.0.0", providedJar)),
                result);
    }

    @Test
    void providedOverlapUsesTheCompleteArtifactVariant() {
        ResolvedClasspathPackage plainProvided =
                dependency(
                        "com.example",
                        "native",
                        "1.0.0",
                        DependencyScope.PROVIDED,
                        Path.of("native-1.0.0.jar"),
                        Optional.empty());
        ResolvedClasspathPackage linuxRuntime =
                dependency(
                        "com.example",
                        "native",
                        "1.0.0",
                        DependencyScope.RUNTIME,
                        Path.of("native-1.0.0-linux.jar"),
                        Optional.of("linux"));

        assertEquals(
                List.of(runtimeJar(linuxRuntime)),
                selector.runtimeJarsWithoutProvidedDuplicates(
                        List.of(plainProvided, linuxRuntime)));
        assertEquals(
                List.of(runtimeJar(plainProvided)),
                selector.runtimeJarsWithoutProvidedDuplicates(List.of(
                        dependency(
                                "com.example",
                                "native",
                                "1.0.0",
                                DependencyScope.RUNTIME,
                                Path.of("native-1.0.0.jar"),
                                Optional.empty()),
                        dependency(
                                "com.example",
                                "native",
                                "1.0.0",
                                DependencyScope.PROVIDED,
                                Path.of("native-1.0.0-linux.jar"),
                                Optional.of("linux")))));
    }

    @Test
    void matchingProvidedVariantIsOmittedWhileTwoRuntimeClassifiersCoexist() {
        ResolvedClasspathPackage linuxProvided =
                dependency(
                        "com.example",
                        "native",
                        "1.0.0",
                        DependencyScope.PROVIDED,
                        Path.of("native-1.0.0-linux.jar"),
                        Optional.of("linux"));
        ResolvedClasspathPackage linuxRuntime =
                dependency(
                        "com.example",
                        "native",
                        "1.0.0",
                        DependencyScope.RUNTIME,
                        Path.of("runtime/native-1.0.0-linux.jar"),
                        Optional.of("linux"));
        ResolvedClasspathPackage macRuntime =
                dependency(
                        "com.example",
                        "native",
                        "1.0.0",
                        DependencyScope.RUNTIME,
                        Path.of("native-1.0.0-macos.jar"),
                        Optional.of("macos"));

        assertEquals(
                List.of(runtimeJar(macRuntime)),
                selector.runtimeJarsWithoutProvidedDuplicates(
                        List.of(linuxProvided, linuxRuntime, macRuntime)));
        assertNotEquals(
                runtimeJar(linuxRuntime).artifactIdentity().nestedJarName(),
                runtimeJar(macRuntime).artifactIdentity().nestedJarName());
    }

    private static ResolvedClasspathPackage dependency(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            Path jar) {
        return new ResolvedClasspathPackage(
                new ResolvedPackage(
                        new PackageId(group, artifact),
                        version,
                        false,
                        Path.of("pom.xml"),
                        jar),
                scope);
    }

    private static ResolvedClasspathPackage dependency(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            Path jar,
            Optional<String> classifier) {
        PackageId packageId = new PackageId(group, artifact);
        return new ResolvedClasspathPackage(
                new ResolvedPackage(
                        packageId,
                        version,
                        false,
                        Path.of("pom.xml"),
                        jar,
                        NestedArtifactIdentity.of(
                                packageId,
                                version,
                                new LockArtifactVariant("jar", classifier),
                                SourceKind.EXTERNAL)),
                scope);
    }

    private static PackageRuntimeJar runtimeJar(
            ResolvedClasspathPackage resolvedPackage) {
        return new PackageRuntimeJar(
                resolvedPackage.resolvedPackage().packageId(),
                resolvedPackage.resolvedPackage().selectedVersion(),
                resolvedPackage.resolvedPackage().jarPath(),
                resolvedPackage.resolvedPackage().artifactIdentity());
    }
}
