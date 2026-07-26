package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.createJarWithEntry;
import static sh.zolt.build.packaging.PackageServiceTestSupport.source;
import static sh.zolt.build.packaging.PackageNestedArtifactAuthorityTestFixtures.config;
import static sh.zolt.build.packaging.PackageNestedArtifactAuthorityTestFixtures.lockPackage;
import static sh.zolt.build.packaging.PackageNestedArtifactAuthorityTestFixtures.lockfile;
import static sh.zolt.build.packaging.PackageNestedArtifactAuthorityTestFixtures.packages;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.packageevidence.PackageEvidenceManifest;
import sh.zolt.build.packageevidence.PackageEvidenceManifestReader;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanDependency;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.SpringBootLoaderArtifact;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageNestedArtifactAuthorityTest {
    @TempDir
    private Path tempDir;

    @Test
    void nestedModesKeepVariantsCollisionsPlanArchiveAndEvidenceAligned()
            throws IOException {
        verifyMode(PackageMode.WAR, false);
        verifyMode(PackageMode.SPRING_BOOT, false);
        verifyMode(PackageMode.SPRING_BOOT_WAR, false);
    }

    @Test
    void providedPackagingAuthorityIsIndependentOfDeclarationOrder()
            throws IOException {
        verifyMode(PackageMode.WAR, true);
        verifyMode(PackageMode.SPRING_BOOT_WAR, true);
    }

    private void verifyMode(
            PackageMode mode,
            boolean reverseDeclarations) throws IOException {
        Path projectRoot = tempDir.resolve(
                mode.configValue()
                        + (reverseDeclarations ? "-reversed" : ""));
        Path cacheRoot = projectRoot.resolve("cache");
        Files.createDirectories(projectRoot);
        List<LockPackage> packages = packages(mode);
        if (reverseDeclarations) {
            List<LockPackage> reversed = new ArrayList<>(packages);
            java.util.Collections.reverse(reversed);
            packages = List.copyOf(reversed);
        }
        for (LockPackage lockPackage : packages) {
            if (lockPackage.jar().isPresent()) {
                createJarWithEntry(
                        cacheRoot.resolve(lockPackage.jar().orElseThrow()),
                        SpringBootLoaderArtifact.isDefaultLoader(lockPackage)
                                ? mode == PackageMode.SPRING_BOOT
                                        ? "org/springframework/boot/loader/launch/JarLauncher.class"
                                        : "org/springframework/boot/loader/launch/WarLauncher.class"
                                : "fixtures/"
                                        + NestedArtifactIdentity.of(lockPackage)
                                                .nestedJarName());
            }
        }
        Files.writeString(projectRoot.resolve("zolt.lock"), lockfile(packages));
        source(projectRoot, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        ProjectConfig projectConfig = config(mode);
        PackagePlan plan = new PackagePlanService().plan(
                projectRoot,
                projectConfig,
                projectRoot.resolve("zolt.lock"),
                cacheRoot);

        PackageService packageService = new PackageService();
        PackageResult result =
                packageService.packageJar(
                        projectRoot,
                        projectConfig,
                        cacheRoot);
        PackageEvidenceManifest evidence = new PackageEvidenceManifestReader()
                .read(result.evidenceManifestPath().orElseThrow());

        assertEquals(plan.dependencies(), evidence.dependencies());
        List<String> nestedLocations = plan.dependencies().stream()
                .map(PackagePlanDependency::location)
                .filter(location -> !location.isBlank())
                .toList();
        assertEquals(nestedLocations.size(), nestedLocations.stream().distinct().count());
        try (JarFile archive = new JarFile(result.jarPath().toFile())) {
            for (PackagePlanDependency dependency : plan.dependencies()) {
                if (!dependency.location().startsWith("BOOT-INF/lib/")
                        && !dependency.location().startsWith("WEB-INF/lib/")
                        && !dependency.location().startsWith(
                                "WEB-INF/lib-provided/")) {
                    continue;
                }
                assertNotNull(
                        archive.getEntry(dependency.location()),
                        dependency.coordinate());
            }
            if (mode == PackageMode.SPRING_BOOT) {
                assertNotNull(archive.getEntry(
                        "org/springframework/boot/loader/launch/JarLauncher.class"));
            }
            if (mode == PackageMode.SPRING_BOOT_WAR) {
                assertNotNull(archive.getEntry(
                        "org/springframework/boot/loader/launch/WarLauncher.class"));
            }
        }

        PackagePlanDependency linux =
                dependency(plan, "com.example:native:linux:jar:1.0.0");
        PackagePlanDependency macos =
                dependency(plan, "com.example:native:macos:jar:1.0.0");
        assertEquals("included", linux.disposition());
        assertEquals("included", macos.disposition());
        assertNotEquals(linux.location(), macos.location());
        assertTrue(plan.dependencies().stream()
                .filter(dependency -> dependency.coordinate().startsWith("com.bridge:native:"))
                .anyMatch(dependency ->
                        dependency.scope() == DependencyScope.RUNTIME
                                && "included".equals(dependency.disposition())));
        if (mode == PackageMode.WAR
                || mode == PackageMode.SPRING_BOOT_WAR) {
            PackagePlanDependency transitivelyProvidedRuntime = dependency(
                    plan,
                    "com.transitive:shared:1.0.0",
                    DependencyScope.RUNTIME);
            assertEquals(
                    "included",
                    transitivelyProvidedRuntime.disposition());
            PackagePlanDependency directlyProvidedRuntime = dependency(
                    plan,
                    "com.direct:shared:1.0.0",
                    DependencyScope.RUNTIME);
            assertEquals("omitted", directlyProvidedRuntime.disposition());
            assertEquals(
                    mode == PackageMode.SPRING_BOOT_WAR
                            ? "spring-boot-war-provided-coordinate-override"
                            : "war-provided-coordinate-override",
                    directlyProvidedRuntime.ruleName());
            try (JarFile archive =
                    new JarFile(result.jarPath().toFile())) {
                assertNotNull(archive.getEntry(
                        transitivelyProvidedRuntime.location()));
                assertNull(archive.getEntry(
                        "WEB-INF/lib/"
                                + NestedArtifactIdentity.of(lockPackage(
                                        "com.direct",
                                        "shared",
                                        "1.0.0",
                                        DependencyScope.RUNTIME,
                                        false,
                                        "com/direct/shared/1.0.0/shared-1.0.0.jar"))
                                        .nestedJarName()));
                if (mode == PackageMode.SPRING_BOOT_WAR) {
                    PackagePlanDependency transitiveProvided =
                            dependency(
                                    plan,
                                    "com.transitive:shared:1.0.0",
                                    DependencyScope.PROVIDED);
                    assertEquals(
                            "omitted",
                            transitiveProvided.disposition());
                    assertEquals(
                            "spring-boot-war-runtime-coordinate-selected",
                            transitiveProvided.ruleName());
                    String nestedName = NestedArtifactIdentity.of(
                                    lockPackage(
                                            "com.transitive",
                                            "shared",
                                            "1.0.0",
                                            DependencyScope.RUNTIME,
                                            false,
                                            "com/transitive/shared/1.0.0/shared-1.0.0.jar"))
                            .nestedJarName();
                    assertNotNull(archive.getEntry(
                            "WEB-INF/lib/" + nestedName));
                    assertNull(archive.getEntry(
                            "WEB-INF/lib-provided/" + nestedName));
                    assertEquals(
                            1,
                            archive.stream()
                                    .filter(entry -> entry
                                            .getName()
                                            .endsWith(nestedName))
                                    .count());
                    PackagePlanDependency providedOnly =
                            dependency(
                                    plan,
                                    "com.transitive:container-api:1.0.0",
                                    DependencyScope.PROVIDED);
                    assertEquals(
                            "provided",
                            providedOnly.disposition());
                    assertTrue(providedOnly.location().startsWith(
                            "WEB-INF/lib-provided/"));
                    assertNotNull(archive.getEntry(
                            providedOnly.location()));
                }
            }
        }
        if (mode == PackageMode.SPRING_BOOT
                || mode == PackageMode.SPRING_BOOT_WAR) {
            PackagePlanDependency defaultLoader = dependency(
                    plan,
                    "org.springframework.boot:spring-boot-loader:4.0.6",
                    DependencyScope.RUNTIME);
            PackagePlanDependency testsLoader = dependency(
                    plan,
                    "org.springframework.boot:spring-boot-loader:tests:jar:4.0.6");
            PackagePlanDependency fixturesLoader = dependency(
                    plan,
                    "org.springframework.boot:spring-boot-loader:fixtures:jar:4.0.6");

            assertEquals("loader", defaultLoader.disposition());
            assertEquals("archive root", defaultLoader.location());
            assertEquals("included", testsLoader.disposition());
            assertEquals("included", fixturesLoader.disposition());
            assertNotEquals(
                    testsLoader.location(),
                    fixturesLoader.location());
            if (mode == PackageMode.SPRING_BOOT_WAR) {
                PackagePlanDependency providedLoader =
                        dependency(
                                plan,
                                "org.springframework.boot:spring-boot-loader:4.0.6",
                                DependencyScope.PROVIDED);
                assertEquals(
                        "omitted",
                        providedLoader.disposition());
                assertEquals(
                        "spring-boot-war-loader-expanded",
                        providedLoader.ruleName());
            }
        }
    }

    private static PackagePlanDependency dependency(
            PackagePlan plan,
            String coordinateFragment) {
        return plan.dependencies().stream()
                .filter(dependency -> dependency.coordinate().contains(coordinateFragment))
                .findFirst()
                .orElseThrow();
    }

    private static PackagePlanDependency dependency(
            PackagePlan plan,
            String coordinateFragment,
            DependencyScope scope) {
        return plan.dependencies().stream()
                .filter(dependency ->
                        dependency.coordinate().contains(coordinateFragment))
                .filter(dependency -> dependency.scope() == scope)
                .findFirst()
                .orElseThrow();
    }

}
