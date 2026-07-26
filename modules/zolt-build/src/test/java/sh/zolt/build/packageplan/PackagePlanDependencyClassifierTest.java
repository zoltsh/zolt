package sh.zolt.build.packageplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import sh.zolt.build.packageauthority.ProvidedPackagingOverrides;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.framework.FrameworkPackagePlanDependency;
import sh.zolt.framework.FrameworkPackagePlanRules;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PackagePlanDependencyClassifierTest {
    @Test
    void springBootWarPlacesProvidedDependenciesInProvidedLib() {
        LockPackage provided = lockPackage(
                "jakarta.servlet",
                "jakarta.servlet-api",
                "6.1.0",
                DependencyScope.PROVIDED,
                true,
                "jakarta/servlet/jakarta.servlet-api/6.1.0/jakarta.servlet-api-6.1.0.jar");
        PackagePlanDependency dependency = PackagePlanDependencyClassifier.dependency(
                PackageMode.SPRING_BOOT_WAR,
                provided,
                overrides(),
                Optional.empty(),
                null);

        assertEquals("jakarta.servlet:jakarta.servlet-api:6.1.0", dependency.coordinate());
        assertEquals("provided", dependency.disposition());
        assertEquals("spring-boot-war-provided-lib", dependency.ruleName());
        assertEquals(
                "WEB-INF/lib-provided/"
                        + NestedArtifactIdentity.of(provided).nestedJarName(),
                dependency.location());
        assertEquals("provided-container", dependency.laneDisposition());
    }

    @Test
    void springBootModesExpandOnlyTheDefaultLoaderVariant() {
        LockPackage defaultLoader = lockPackage(
                "org.springframework.boot",
                "spring-boot-loader",
                "4.0.6",
                DependencyScope.RUNTIME,
                false,
                "org/springframework/boot/spring-boot-loader/4.0.6/spring-boot-loader-4.0.6.jar");
        LockPackage testsLoader = lockPackage(
                "org.springframework.boot",
                "spring-boot-loader",
                "4.0.6",
                DependencyScope.RUNTIME,
                true,
                "org/springframework/boot/spring-boot-loader/4.0.6/spring-boot-loader-4.0.6-tests.jar");

        for (PackageMode mode : List.of(
                PackageMode.SPRING_BOOT,
                PackageMode.SPRING_BOOT_WAR)) {
            PackagePlanDependency expanded =
                    PackagePlanDependencyClassifier.dependency(
                            mode,
                            defaultLoader,
                            overrides(),
                            Optional.empty(),
                            null);
            PackagePlanDependency nested =
                    PackagePlanDependencyClassifier.dependency(
                            mode,
                            testsLoader,
                            overrides(),
                            Optional.empty(),
                            null);

            assertEquals("loader", expanded.disposition());
            assertEquals("archive root", expanded.location());
            assertEquals("included", nested.disposition());
            assertEquals(
                    (mode == PackageMode.SPRING_BOOT
                                    ? "BOOT-INF/lib/"
                                    : "WEB-INF/lib/")
                            + NestedArtifactIdentity.of(testsLoader)
                                    .nestedJarName(),
                    nested.location());
        }
    }

    @Test
    void warOmitsRuntimeCoordinateWhenSameCoordinateIsDirectProvidedDependency() {
        PackageId shared = new PackageId("org.apache.tomcat.embed", "tomcat-embed-core");
        LockPackage provided = lockPackage(
                shared.groupId(),
                shared.artifactId(),
                "10.1.40",
                DependencyScope.PROVIDED,
                true,
                "org/apache/tomcat/embed/tomcat-embed-core/10.1.40/tomcat-embed-core-10.1.40.jar");

        PackagePlanDependency dependency = PackagePlanDependencyClassifier.dependency(
                PackageMode.WAR,
                lockPackage(
                        shared.groupId(),
                        shared.artifactId(),
                        "10.1.40",
                        DependencyScope.RUNTIME,
                        false,
                        "org/apache/tomcat/embed/tomcat-embed-core/10.1.40/tomcat-embed-core-10.1.40.jar"),
                overrides(provided),
                Optional.empty(),
                null);

        assertEquals("org.apache.tomcat.embed:tomcat-embed-core:10.1.40", dependency.coordinate());
        assertEquals("omitted", dependency.disposition());
        assertEquals("war-provided-coordinate-override", dependency.ruleName());
        assertEquals("", dependency.location());
    }

    @Test
    void transitiveProvidedReachabilityDoesNotOverrideRuntimePackaging() {
        LockPackage provided = lockPackage(
                "com.example",
                "shared-util",
                "1.0.0",
                DependencyScope.PROVIDED,
                false,
                "com/example/shared-util/1.0.0/shared-util-1.0.0.jar");
        LockPackage runtime = lockPackage(
                "com.example",
                "shared-util",
                "1.0.0",
                DependencyScope.RUNTIME,
                false,
                "com/example/shared-util/1.0.0/shared-util-1.0.0.jar");

        for (PackageMode mode : List.of(
                PackageMode.WAR,
                PackageMode.SPRING_BOOT_WAR)) {
            PackagePlanDependency dependency =
                    PackagePlanDependencyClassifier.dependency(
                            mode,
                            runtime,
                            overrides(provided),
                            Optional.empty(),
                            null);

            assertEquals("included", dependency.disposition());
            assertEquals(nestedLocation(runtime), dependency.location());
        }
    }

    @Test
    void directProvidedDefaultLoaderDoesNotOverrideSpringBootWarTooling() {
        LockPackage provided = lockPackage(
                "org.springframework.boot",
                "spring-boot-loader",
                "4.0.6",
                DependencyScope.PROVIDED,
                true,
                "org/springframework/boot/spring-boot-loader/4.0.6/spring-boot-loader-4.0.6.jar");
        LockPackage runtime = lockPackage(
                "org.springframework.boot",
                "spring-boot-loader",
                "4.0.6",
                DependencyScope.RUNTIME,
                false,
                "org/springframework/boot/spring-boot-loader/4.0.6/spring-boot-loader-4.0.6.jar");

        PackagePlanDependency dependency =
                PackagePlanDependencyClassifier.dependency(
                        PackageMode.SPRING_BOOT_WAR,
                        runtime,
                        overrides(provided),
                        Optional.empty(),
                        null);

        assertEquals("loader", dependency.disposition());
        assertEquals("archive root", dependency.location());
    }

    @Test
    void warModesKeepPlainAndClassifiedVariantsIndependent() {
        for (PackageMode mode : List.of(PackageMode.WAR, PackageMode.SPRING_BOOT_WAR)) {
            LockPackage plainProvided = lockPackage(
                    "com.example",
                    "native",
                    "1.0.0",
                    DependencyScope.PROVIDED,
                    true,
                    "com/example/native/1.0.0/native-1.0.0.jar");
            LockPackage classifiedRuntime = lockPackage(
                    "com.example",
                    "native",
                    "1.0.0",
                    DependencyScope.RUNTIME,
                    false,
                    "com/example/native/1.0.0/native-1.0.0-linux.jar");

            PackagePlanDependency dependency =
                    PackagePlanDependencyClassifier.dependency(
                            mode,
                            classifiedRuntime,
                            overrides(plainProvided),
                            Optional.empty(),
                            null);

            assertEquals("included", dependency.disposition());
            assertEquals(
                    nestedLocation(classifiedRuntime),
                    dependency.location());
        }
    }

    @Test
    void warModesKeepClassifiedProvidedAndPlainRuntimeIndependent() {
        for (PackageMode mode : List.of(PackageMode.WAR, PackageMode.SPRING_BOOT_WAR)) {
            LockPackage classifiedProvided = lockPackage(
                    "com.example",
                    "native",
                    "1.0.0",
                    DependencyScope.PROVIDED,
                    true,
                    "com/example/native/1.0.0/native-1.0.0-linux.jar");
            LockPackage plainRuntime = lockPackage(
                    "com.example",
                    "native",
                    "1.0.0",
                    DependencyScope.RUNTIME,
                    false,
                    "com/example/native/1.0.0/native-1.0.0.jar");

            PackagePlanDependency dependency =
                    PackagePlanDependencyClassifier.dependency(
                            mode,
                            plainRuntime,
                            overrides(classifiedProvided),
                            Optional.empty(),
                            null);

            assertEquals("included", dependency.disposition());
            assertEquals(
                    nestedLocation(plainRuntime),
                    dependency.location());
        }
    }

    @Test
    void warModesOmitOnlyMatchingVariantAndGiveClassifiersUniqueNames() {
        LockPackage linux = lockPackage(
                "com.example",
                "native",
                "1.0.0",
                DependencyScope.RUNTIME,
                false,
                "com/example/native/1.0.0/native-1.0.0-linux.jar");
        LockPackage macos = lockPackage(
                "com.example",
                "native",
                "1.0.0",
                DependencyScope.RUNTIME,
                false,
                "com/example/native/1.0.0/native-1.0.0-macos.jar");
        LockPackage linuxProvided = lockPackage(
                "com.example",
                "native",
                "1.0.0",
                DependencyScope.PROVIDED,
                true,
                "com/example/native/1.0.0/native-1.0.0-linux.jar");
        assertNotEquals(
                NestedArtifactIdentity.of(linux).nestedJarName(),
                NestedArtifactIdentity.of(macos).nestedJarName());

        for (PackageMode mode : List.of(PackageMode.WAR, PackageMode.SPRING_BOOT_WAR)) {
            PackagePlanDependency matching =
                    PackagePlanDependencyClassifier.dependency(
                            mode,
                            linux,
                            overrides(linuxProvided),
                            Optional.empty(),
                            null);
            PackagePlanDependency other =
                    PackagePlanDependencyClassifier.dependency(
                            mode,
                            macos,
                            overrides(linuxProvided),
                            Optional.empty(),
                            null);

            assertEquals("omitted", matching.disposition());
            assertEquals("included", other.disposition());
            assertEquals(nestedLocation(macos), other.location());
        }
    }

    @Test
    void quarkusUsesFrameworkRulesWhenConfigured() {
        FrameworkPackagePlanRules rules = new FrameworkPackagePlanRules() {
            @Override
            public boolean supports(PackageMode mode) {
                return mode == PackageMode.QUARKUS;
            }

            @Override
            public FrameworkPackagePlanDependency dependency(LockPackage lockPackage, ProjectConfig config) {
                return new FrameworkPackagePlanDependency(
                        "io.quarkus:quarkus-rest:3.33.0",
                        "3.33.0",
                        DependencyScope.RUNTIME,
                        "included",
                        "quarkus-runtime-lib",
                        "target/quarkus-app/lib/quarkus-rest-3.33.0.jar",
                        "Quarkus runtime dependency is copied into the fast-jar lib directory",
                        List.of("strict-version: io.quarkus:quarkus-rest -> 3.33.0"));
            }

            @Override
            public Path archivePath(Path projectRoot, ProjectConfig config) {
                return projectRoot.resolve("target/quarkus-app/quarkus-run.jar");
            }

            @Override
            public String applicationLayout(ProjectConfig config) {
                return "target/quarkus-app/app";
            }
        };

        PackagePlanDependency dependency = PackagePlanDependencyClassifier.dependency(
                PackageMode.QUARKUS,
                lockPackage(
                        "io.quarkus",
                        "quarkus-rest",
                        "3.33.0",
                        DependencyScope.RUNTIME,
                        true,
                        "io/quarkus/quarkus-rest/3.33.0/quarkus-rest-3.33.0.jar"),
                overrides(),
                Optional.of(rules),
                null);

        assertEquals("included", dependency.disposition());
        assertEquals("quarkus-runtime-lib", dependency.ruleName());
        assertEquals("target/quarkus-app/lib/quarkus-rest-3.33.0.jar", dependency.location());
        assertEquals(List.of("strict-version: io.quarkus:quarkus-rest -> 3.33.0"), dependency.policies());
    }

    private static LockPackage lockPackage(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            boolean direct,
            String jar) {
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                "maven-central",
                scope,
                direct,
                Optional.of(jar),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    private static String nestedLocation(LockPackage lockPackage) {
        return "WEB-INF/lib/"
                + NestedArtifactIdentity.of(lockPackage).nestedJarName();
    }

    private static ProvidedPackagingOverrides overrides(
            LockPackage... packages) {
        StringBuilder config = new StringBuilder("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """);
        List<LockPackage> declarations = List.of(packages).stream()
                .filter(LockPackage::direct)
                .filter(lockPackage -> lockPackage.scope()
                        == DependencyScope.PROVIDED)
                .toList();
        if (!declarations.isEmpty()) {
            config.append("\n[provided.dependencies]\n");
            for (LockPackage declaration : declarations) {
                LockArtifactVariant variant =
                        LockArtifactVariant.of(declaration);
                config.append('"')
                        .append(declaration.packageId())
                        .append("\" = { version = \"")
                        .append(declaration.version())
                        .append('"');
                variant.classifier().ifPresent(classifier -> config
                        .append(", classifier = \"")
                        .append(classifier)
                        .append('"'));
                if (!"jar".equals(variant.extension())) {
                    config.append(", type = \"")
                            .append(variant.extension())
                            .append('"');
                }
                config.append(" }\n");
            }
        }
        return ProvidedPackagingOverrides
                .fromConfigAndLockPackages(
                        new ZoltTomlParser().parse(
                                config.toString()),
                        List.of(packages));
    }
}
