package sh.zolt.build.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.NestedArtifactIdentity.SourceKind;
import sh.zolt.build.packageauthority.ProvidedPackagingOverrides;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
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

        List<PackageRuntimeJar> result =
                runtimeJarsWithoutProvidedDuplicates(
                        List.of(
                                dependency("com.example", "shared", "1.0.0", DependencyScope.RUNTIME, runtimeJar),
                                dependency("com.example", "shared", "1.0.0", DependencyScope.PROVIDED, providedJar),
                                dependency("com.example", "runtime-only", "1.0.0", DependencyScope.RUNTIME, Path.of("runtime.jar"))),
                        PackageMode.WAR);

        assertEquals(List.of(
                        new PackageRuntimeJar(new PackageId("com.example", "runtime-only"), "1.0.0", Path.of("runtime.jar"))),
                result);
    }

    @Test
    void providedJarsSelectOnlyProvidedScope() {
        Path providedJar = Path.of("cache/com/example/provided/1.0.0/provided-1.0.0.jar");

        List<PackageRuntimeJar> result = providedJars(List.of(
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
                runtimeJarsWithoutProvidedDuplicates(
                        List.of(plainProvided, linuxRuntime),
                        PackageMode.WAR));
        assertEquals(
                List.of(runtimeJar(plainProvided)),
                runtimeJarsWithoutProvidedDuplicates(
                        List.of(
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
                                        Optional.of("linux"))),
                        PackageMode.WAR));
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
                runtimeJarsWithoutProvidedDuplicates(
                        List.of(linuxProvided, linuxRuntime, macRuntime),
                        PackageMode.WAR));
        assertNotEquals(
                runtimeJar(linuxRuntime).artifactIdentity().nestedJarName(),
                runtimeJar(macRuntime).artifactIdentity().nestedJarName());
    }

    @Test
    void transitiveProvidedReachabilityNeverSuppressesRequiredRuntimeRegardlessOfOrder() {
        ResolvedClasspathPackage provided = dependency(
                "com.example",
                "shared",
                "1.0.0",
                DependencyScope.PROVIDED,
                Path.of("provided/shared.jar"),
                Optional.empty(),
                false);
        ResolvedClasspathPackage runtime = dependency(
                "com.example",
                "shared",
                "1.0.0",
                DependencyScope.RUNTIME,
                Path.of("runtime/shared.jar"),
                Optional.empty(),
                false);

        for (List<ResolvedClasspathPackage> packages : List.of(
                List.of(provided, runtime),
                List.of(runtime, provided))) {
            assertEquals(
                    List.of(runtimeJar(runtime)),
                    runtimeJarsWithoutProvidedDuplicates(
                            packages,
                            PackageMode.WAR));
        }
    }

    @Test
    void aggregateDirectBitCannotGrantAnotherMemberProvidedAuthority() {
        ResolvedClasspathPackage aggregateDirectProvided = dependency(
                "com.example",
                "shared",
                "1.0.0",
                DependencyScope.PROVIDED,
                Path.of("provided/shared.jar"),
                Optional.empty(),
                true);
        ResolvedClasspathPackage runtime = dependency(
                "com.example",
                "shared",
                "1.0.0",
                DependencyScope.RUNTIME,
                Path.of("runtime/shared.jar"),
                Optional.empty(),
                false);
        List<ResolvedClasspathPackage> packages =
                List.of(aggregateDirectProvided, runtime);
        ProvidedPackagingOverrides memberAuthority =
                ProvidedPackagingOverrides
                        .fromConfigAndClasspathPackages(
                                config(List.of()),
                                packages);

        assertEquals(
                List.of(runtimeJar(runtime)),
                selector.runtimeJarsWithoutProvidedDuplicates(
                        packages,
                        PackageMode.SPRING_BOOT_WAR,
                        memberAuthority));
        assertEquals(
                List.of(),
                selector.providedJars(
                        packages,
                        PackageMode.SPRING_BOOT_WAR,
                        memberAuthority));
    }

    @Test
    void directProvidedDefaultLoaderCannotSuppressSpringBootWarTooling() {
        ResolvedClasspathPackage provided = dependency(
                "org.springframework.boot",
                "spring-boot-loader",
                "4.0.6",
                DependencyScope.PROVIDED,
                Path.of("provided/spring-boot-loader.jar"),
                Optional.empty(),
                true);
        ResolvedClasspathPackage runtime = dependency(
                "org.springframework.boot",
                "spring-boot-loader",
                "4.0.6",
                DependencyScope.RUNTIME,
                Path.of("runtime/spring-boot-loader.jar"),
                Optional.empty(),
                false);

        assertEquals(
                List.of(runtimeJar(runtime)),
                runtimeJarsWithoutProvidedDuplicates(
                        List.of(provided, runtime),
                        PackageMode.SPRING_BOOT_WAR));
        assertEquals(
                List.of(),
                runtimeJarsWithoutProvidedDuplicates(
                        List.of(provided, runtime),
                        PackageMode.WAR));
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
                        scope == DependencyScope.PROVIDED,
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
        return dependency(
                group,
                artifact,
                version,
                scope,
                jar,
                classifier,
                scope == DependencyScope.PROVIDED);
    }

    private static ResolvedClasspathPackage dependency(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            Path jar,
            Optional<String> classifier,
            boolean direct) {
        PackageId packageId = new PackageId(group, artifact);
        return new ResolvedClasspathPackage(
                new ResolvedPackage(
                        packageId,
                        version,
                        direct,
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

    private List<PackageRuntimeJar>
            runtimeJarsWithoutProvidedDuplicates(
                    List<ResolvedClasspathPackage> packages,
                    PackageMode mode) {
        return selector.runtimeJarsWithoutProvidedDuplicates(
                packages,
                mode,
                overrides(packages));
    }

    private List<PackageRuntimeJar> providedJars(
            List<ResolvedClasspathPackage> packages) {
        return selector.providedJars(
                packages,
                PackageMode.SPRING_BOOT_WAR,
                overrides(packages));
    }

    private static ProvidedPackagingOverrides overrides(
            List<ResolvedClasspathPackage> packages) {
        return ProvidedPackagingOverrides
                .fromConfigAndClasspathPackages(
                        config(packages),
                        packages);
    }

    private static ProjectConfig config(
            List<ResolvedClasspathPackage> packages) {
        StringBuilder toml = new StringBuilder("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        List<ResolvedClasspathPackage> declarations = packages.stream()
                .filter(value -> value.scope()
                        == DependencyScope.PROVIDED)
                .filter(value -> value.resolvedPackage().direct())
                .toList();
        if (!declarations.isEmpty()) {
            toml.append("\n[dependencies.provided]\n");
            for (ResolvedClasspathPackage declaration : declarations) {
                var resolved = declaration.resolvedPackage();
                var identity = resolved.artifactIdentity();
                toml.append('"')
                        .append(resolved.packageId())
                        .append("\" = { version = \"")
                        .append(resolved.selectedVersion())
                        .append('"');
                identity.classifier().ifPresent(classifier -> toml
                        .append(", classifier = \"")
                        .append(classifier)
                        .append('"'));
                if (!"jar".equals(identity.extension())) {
                    toml.append(", type = \"")
                            .append(identity.extension())
                            .append('"');
                }
                toml.append(" }\n");
            }
        }
        return new ManifestProjectConfigLoader().load(toml.toString());
    }
}
