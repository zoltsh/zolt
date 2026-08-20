package sh.zolt.manifest.effective;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestSource;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredProjectMetadata;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.manifest.authored.ProjectLocalDomains;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.ToolchainPolicy;

final class EffectiveJavaRuntimeInvariantTest {
    private static final ManifestSource PROJECT =
            new ManifestSource("modules/core/zolt.toml", List.of("project", "name"));

    @Test
    void separatesFeatureBearingMainFromFeatureFreeTestRuntime() {
        EffectiveJavaRuntime.Requested main = requestedMain(21);
        EffectiveTestJavaRuntime.Requested test = requestedTest(17);

        assertEquals(Set.of(JavaFeature.NATIVE_IMAGE), main.features().value());
        assertEquals(17, test.version().value().value());
        assertDoesNotThrow(() -> new EffectiveToolchains(
                Optional.empty(), Optional.of(main), Optional.of(test)));
        assertDoesNotThrow(() -> new EffectiveToolchains(
                Optional.empty(),
                Optional.of(main),
                Optional.of(new EffectiveTestJavaRuntime.SameAsMain(main))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EffectiveToolchains(
                        Optional.empty(),
                        Optional.of(main),
                        Optional.of(new EffectiveTestJavaRuntime.SameAsMain(requestedMain(22)))));
    }

    @Test
    void enforcesBomAndNonBomJavaShapesAndCompatibility() {
        EffectiveProjectIdentity java21 = identity(Optional.of(new JavaFeatureRelease(21)));
        EffectiveProjectIdentity noJava = identity(Optional.empty());
        EffectiveJavaRuntime main21 = requestedMain(21);
        EffectiveJavaRuntime system21 = systemMain(21);

        assertDoesNotThrow(() -> project(
                java21,
                paired(main21, new EffectiveTestJavaRuntime.SameAsMain(main21)),
                AuthoredPackaging.empty()));
        assertDoesNotThrow(() -> project(
                java21,
                paired(main21, requestedTest(21)),
                AuthoredPackaging.empty()));
        assertDoesNotThrow(() -> project(
                java21,
                paired(system21, new EffectiveTestJavaRuntime.SameAsMain(system21)),
                AuthoredPackaging.empty()));
        assertDoesNotThrow(() -> project(
                noJava,
                EffectiveToolchains.withoutJava(Optional.empty()),
                bomPackaging()));

        List<Executable> invalid = List.of(
                () -> project(
                        noJava,
                        EffectiveToolchains.withoutJava(Optional.empty()),
                        AuthoredPackaging.empty()),
                () -> project(
                        java21,
                        paired(main21, new EffectiveTestJavaRuntime.SameAsMain(main21)),
                        bomPackaging()),
                () -> project(
                        noJava,
                        paired(main21, new EffectiveTestJavaRuntime.SameAsMain(main21)),
                        bomPackaging()),
                () -> project(
                        java21,
                        paired(requestedMain(17), requestedTest(21)),
                        AuthoredPackaging.empty()),
                () -> project(
                        java21,
                        paired(systemMain(17), requestedTest(21)),
                        AuthoredPackaging.empty()),
                () -> project(
                        java21,
                        paired(
                                requestedMain(17),
                                new EffectiveTestJavaRuntime.SameAsMain(requestedMain(17))),
                        AuthoredPackaging.empty()),
                () -> project(
                        java21,
                        paired(main21, requestedTest(17)),
                        AuthoredPackaging.empty()));
        invalid.forEach(candidate -> assertThrows(IllegalArgumentException.class, candidate));
    }

    @Test
    void rejectsVirtualWorkspaceAuthoredInputForEffectiveManifest() {
        EffectiveProject bomProject = project(
                identity(Optional.empty()),
                EffectiveToolchains.withoutJava(Optional.empty()),
                bomPackaging());

        assertThrows(
                IllegalArgumentException.class,
                () -> new EffectiveManifest(virtualWorkspace(), Optional.empty(), bomProject));
    }

    private static EffectiveProject project(
            EffectiveProjectIdentity identity,
            EffectiveToolchains toolchains,
            AuthoredPackaging packaging) {
        return new EffectiveProject(
                identity,
                new EffectiveSharedConfiguration(
                        Map.of(),
                        defaultRepositories(),
                        Map.of(),
                        Map.of(),
                        toolchains,
                        EffectiveCoverage.empty(),
                        EffectiveCommands.empty()),
                new ProjectLocalDomains(
                        AuthoredProjectMetadata.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        packaging,
                        Optional.empty()));
    }

    private static EffectiveProjectIdentity identity(Optional<JavaFeatureRelease> javaRelease) {
        return new EffectiveProjectIdentity(
                EffectiveValue.authored(new ProjectName("core"), PROJECT),
                EffectiveValue.authored(
                        new ProjectVersion("1.0.0"), source(List.of("project", "version"))),
                EffectiveValue.authored(
                        new ProjectGroup("com.example"), source(List.of("project", "group"))),
                javaRelease.map(value ->
                        EffectiveValue.authored(value, source(List.of("project", "java")))),
                Optional.empty());
    }

    private static EffectiveJavaRuntime.Requested requestedMain(int version) {
        return new EffectiveJavaRuntime.Requested(
                EffectiveValue.builtIn(new JavaFeatureRelease(version)),
                EffectiveValue.builtIn(JavaDistribution.GRAALVM_COMMUNITY),
                EffectiveValue.builtIn(Set.of(JavaFeature.NATIVE_IMAGE)),
                EffectiveValue.builtIn(ToolchainPolicy.REQUIRE_MANAGED));
    }

    private static EffectiveJavaRuntime.System systemMain(int version) {
        return new EffectiveJavaRuntime.System(
                EffectiveValue.authored(
                        new JavaFeatureRelease(version), source(List.of("project", "java"))));
    }

    private static EffectiveTestJavaRuntime.Requested requestedTest(int version) {
        return new EffectiveTestJavaRuntime.Requested(
                EffectiveValue.builtIn(new JavaFeatureRelease(version)),
                EffectiveValue.builtIn(JavaDistribution.TEMURIN),
                EffectiveValue.builtIn(ToolchainPolicy.PREFER_MANAGED));
    }

    private static EffectiveToolchains paired(
            EffectiveJavaRuntime main,
            EffectiveTestJavaRuntime test) {
        return new EffectiveToolchains(
                Optional.empty(), Optional.of(main), Optional.of(test));
    }

    private static AuthoredPackaging bomPackaging() {
        return new AuthoredPackaging(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new AuthoredBom(
                        Optional.empty(), Optional.of(Map.of()), Optional.empty())));
    }

    private static EffectiveDependencyRepositories defaultRepositories() {
        return new EffectiveDependencyRepositories(
                EffectiveValue.builtIn(EffectiveCentralRepository.enabled(
                        DependencyRepository.unauthenticated(
                                AuthoredDependencyRepositories.MAVEN_CENTRAL_URL))),
                Map.of(),
                EffectiveValue.builtIn(List.of(new LocalId("central"))));
    }

    private static AuthoredManifest virtualWorkspace() {
        return new AuthoredManifest(
                Optional.of(new AuthoredWorkspace(
                        new LocalId("workspace"),
                        new AuthoredWorkspaceMembers(
                                List.of(new WorkspaceMemberPattern("modules/*")),
                                List.of(),
                                Optional.empty()),
                        Optional.empty())),
                Optional.empty(),
                AuthoredToolchains.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                AuthoredBuildConfiguration.empty(),
                Optional.empty(),
                AuthoredPackaging.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static ManifestSource source(List<String> fieldPath) {
        return new ManifestSource("modules/core/zolt.toml", fieldPath);
    }
}
