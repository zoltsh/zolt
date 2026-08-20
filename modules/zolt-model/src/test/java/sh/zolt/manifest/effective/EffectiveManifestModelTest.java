package sh.zolt.manifest.effective;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.CoveragePercentage;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestSource;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredProject;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredProjectMetadata;
import sh.zolt.manifest.authored.AuthoredTask;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.manifest.authored.ProjectLocalDomains;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.ToolchainPolicy;

final class EffectiveManifestModelTest {
    private static final ManifestSource ROOT_GROUP =
            new ManifestSource("zolt.toml", "workspace.project.group");
    private static final ManifestSource MEMBER_NAME =
            new ManifestSource("modules/core/zolt.toml", "project.name");
    private static final ManifestSource MEMBER_VERSION =
            new ManifestSource("modules/core/zolt.toml", "project.version");

    @Test
    void retainsPerFieldIdentityAndCoverageProvenance() {
        EffectiveProjectIdentity identity = new EffectiveProjectIdentity(
                EffectiveValue.authored(new ProjectName("core"), MEMBER_NAME),
                EffectiveValue.authored(new ProjectVersion("1.0.0"), MEMBER_VERSION),
                EffectiveValue.inherited(new ProjectGroup("com.example"), ROOT_GROUP),
                Optional.of(EffectiveValue.inherited(
                        new JavaFeatureRelease(21),
                        new ManifestSource("zolt.toml", "workspace.project.java"))),
                Optional.empty());
        EffectiveCoverage coverage = new EffectiveCoverage(
                Optional.of(EffectiveValue.inherited(
                        new CoveragePercentage(88),
                        new ManifestSource("zolt.toml", "coverage.line"))),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        assertEquals(ValueOrigin.AUTHORED, identity.name().origin());
        assertEquals(ValueOrigin.INHERITED, identity.group().origin());
        assertEquals(ROOT_GROUP, identity.group().source().orElseThrow());
        assertEquals(ValueOrigin.INHERITED, identity.javaRelease().orElseThrow().origin());
        assertEquals(ValueOrigin.INHERITED, coverage.line().orElseThrow().origin());
    }

    @Test
    void freezesRepositoryUniverseAndExactOrder() {
        LocalId internal = new LocalId("internal");
        DependencyRepository repository = DependencyRepository.unauthenticated(
                new RepositoryUrl("https://repo.example.test/maven"));
        Map<LocalId, EffectiveValue<DependencyRepository>> named = new HashMap<>();
        named.put(internal, EffectiveValue.authored(
                repository, new ManifestSource("zolt.toml", "repositories.internal")));
        List<LocalId> order = new ArrayList<>(List.of(internal));

        EffectiveDependencyRepositories repositories = new EffectiveDependencyRepositories(
                EffectiveValue.authored(
                        EffectiveCentralRepository.disabled(),
                        new ManifestSource("zolt.toml", "repositories.central")),
                named,
                EffectiveValue.authored(
                        order, new ManifestSource("zolt.toml", "repositories.order")));
        named.clear();
        order.clear();

        assertFalse(repositories.central().value().enabled());
        assertEquals(ValueOrigin.AUTHORED, repositories.central().origin());
        assertEquals(Set.of(internal), repositories.named().keySet());
        assertEquals(List.of(internal), repositories.lookupOrder().value());
        assertThrows(
                UnsupportedOperationException.class,
                () -> repositories.named().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> repositories.lookupOrder().value().add(new LocalId("other")));
    }

    @Test
    void rejectsIncompleteOrDuplicateEffectiveRepositoryOrder() {
        EffectiveValue<EffectiveCentralRepository> central = EffectiveValue.builtIn(
                EffectiveCentralRepository.enabled(DependencyRepository.unauthenticated(
                        AuthoredDependencyRepositories.MAVEN_CENTRAL_URL)));
        LocalId internal = new LocalId("internal");
        Map<LocalId, EffectiveValue<DependencyRepository>> named = Map.of(
                internal,
                EffectiveValue.authored(
                        DependencyRepository.unauthenticated(
                                new RepositoryUrl("https://repo.example.test/maven")),
                        new ManifestSource("zolt.toml", "repositories.internal")));

        assertThrows(
                IllegalArgumentException.class,
                () -> new EffectiveDependencyRepositories(
                        central, named, EffectiveValue.builtIn(List.of(internal))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EffectiveDependencyRepositories(
                        central,
                        named,
                        EffectiveValue.builtIn(List.of(internal, internal, new LocalId("central")))));
    }

    @Test
    void freezesSharedMapsCommandsAndJavaFeatures() {
        LocalId alias = new LocalId("library");
        Map<LocalId, EffectiveValue<VersionAliasValue>> versions = new HashMap<>();
        versions.put(alias, EffectiveValue.authored(
                new VersionAliasValue("1.0.0"),
                new ManifestSource("modules/core/zolt.toml", "versions.library")));
        Map<LocalId, EffectiveValue<AuthoredTask>> tasks = new HashMap<>();
        tasks.put(new LocalId("audit"), EffectiveValue.inherited(
                new AuthoredTask(Optional.empty(), List.of("audit.sh"), Optional.empty(), Map.of()),
                new ManifestSource("zolt.toml", "tasks.audit")));
        Set<JavaFeature> features = new HashSet<>(Set.of(JavaFeature.NATIVE_IMAGE));

        EffectiveJavaRuntime.Requested mainJava = new EffectiveJavaRuntime.Requested(
                EffectiveValue.authored(
                        new JavaFeatureRelease(21),
                        new ManifestSource("modules/core/zolt.toml", "toolchain.java.version")),
                EffectiveValue.builtIn(JavaDistribution.TEMURIN),
                EffectiveValue.authored(
                        features,
                        new ManifestSource("modules/core/zolt.toml", "toolchain.java.features")),
                EffectiveValue.builtIn(ToolchainPolicy.PREFER_MANAGED));
        EffectiveSharedConfiguration shared = new EffectiveSharedConfiguration(
                versions,
                defaultRepositories(),
                Map.of(),
                Map.of(),
                new EffectiveToolchains(
                        Optional.empty(),
                        Optional.of(mainJava),
                        Optional.of(new EffectiveTestJavaRuntime.SameAsMain(mainJava))),
                EffectiveCoverage.empty(),
                new EffectiveCommands(tasks, Map.of()));
        versions.clear();
        tasks.clear();
        features.clear();

        assertEquals(Set.of(alias), shared.versions().keySet());
        assertEquals(Set.of(new LocalId("audit")), shared.commands().tasks().keySet());
        assertEquals(Set.of(JavaFeature.NATIVE_IMAGE), mainJava.features().value());
        assertThrows(UnsupportedOperationException.class, () -> shared.versions().clear());
        assertThrows(UnsupportedOperationException.class, () -> mainJava.features().value().clear());
    }

    @Test
    void representsBomWithoutInventingJavaRuntimes() {
        AuthoredManifest authored = authoredBom();
        EffectiveProjectIdentity identity = new EffectiveProjectIdentity(
                EffectiveValue.authored(
                        new ProjectName("catalog"),
                        new ManifestSource("zolt.toml", "project.name")),
                EffectiveValue.authored(
                        new ProjectVersion("1.0.0"),
                        new ManifestSource("zolt.toml", "project.version")),
                EffectiveValue.authored(
                        new ProjectGroup("com.example"),
                        new ManifestSource("zolt.toml", "project.group")),
                Optional.empty(),
                Optional.empty());
        EffectiveToolchains toolchains = EffectiveToolchains.withoutJava(Optional.empty());
        ProjectLocalDomains local = localDomains(authored);
        EffectiveProject project = new EffectiveProject(
                identity,
                new EffectiveSharedConfiguration(
                        Map.of(),
                        defaultRepositories(),
                        Map.of(),
                        Map.of(),
                        toolchains,
                        EffectiveCoverage.empty(),
                        EffectiveCommands.empty()),
                local);
        EffectiveManifest effective = new EffectiveManifest(authored, Optional.empty(), project);

        assertTrue(effective.project().local().packaging().bom().isPresent());
        assertTrue(effective.project().shared().toolchains().mainJava().isEmpty());
        assertTrue(effective.project().shared().toolchains().testJava().isEmpty());
        assertTrue(effective.project().identity().javaRelease().isEmpty());
    }

    @Test
    void rejectsNullContainersAndIncompleteJavaRuntimePairs() {
        assertThrows(
                NullPointerException.class,
                () -> new EffectiveCoverage(null, Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EffectiveToolchains(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new EffectiveTestJavaRuntime.Requested(
                                EffectiveValue.authored(
                                        new JavaFeatureRelease(21),
                                        new ManifestSource("zolt.toml", "toolchain.java.test.version")),
                                EffectiveValue.builtIn(JavaDistribution.TEMURIN),
                                EffectiveValue.builtIn(ToolchainPolicy.PREFER_MANAGED)))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EffectiveToolchains(
                        Optional.empty(),
                        Optional.of(new EffectiveJavaRuntime.System(
                                EffectiveValue.authored(
                                        new JavaFeatureRelease(21),
                                        new ManifestSource("zolt.toml", "project.java")))),
                        Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> new EffectiveManifest(authoredBom(), null, null));
    }

    private static EffectiveDependencyRepositories defaultRepositories() {
        return new EffectiveDependencyRepositories(
                EffectiveValue.builtIn(EffectiveCentralRepository.enabled(
                        DependencyRepository.unauthenticated(
                                AuthoredDependencyRepositories.MAVEN_CENTRAL_URL))),
                Map.of(),
                EffectiveValue.builtIn(List.of(new LocalId("central"))));
    }

    private static AuthoredManifest authoredBom() {
        AuthoredProject project = new AuthoredProject(
                new AuthoredProjectIdentity(
                        new ProjectName("catalog"),
                        Optional.of(new ProjectVersion("1.0.0")),
                        Optional.of(new ProjectGroup("com.example")),
                        Optional.empty(),
                        Optional.empty()),
                AuthoredProjectMetadata.empty());
        AuthoredPackaging packaging = new AuthoredPackaging(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new AuthoredBom(
                        Optional.empty(), Optional.of(Map.of()), Optional.empty())));
        return new AuthoredManifest(
                Optional.empty(),
                Optional.of(project),
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
                packaging,
                Optional.empty(),
                Optional.empty());
    }

    private static ProjectLocalDomains localDomains(AuthoredManifest manifest) {
        AuthoredProject project = manifest.project().orElseThrow();
        return new ProjectLocalDomains(
                project.metadata(),
                manifest.dependencies(),
                manifest.dependencyConstraints(),
                manifest.dependencyPolicy(),
                manifest.build().build(),
                manifest.build().compiler(),
                manifest.build().resources(),
                manifest.build().tests(),
                manifest.generated(),
                manifest.packaging(),
                manifest.publishing());
    }
}
