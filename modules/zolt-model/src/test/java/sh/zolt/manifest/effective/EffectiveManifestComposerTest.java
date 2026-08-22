package sh.zolt.manifest.effective;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.BuiltInCommandCatalog;
import sh.zolt.manifest.CentralRepositoryControl;
import sh.zolt.manifest.CoveragePercentage;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestSource;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.RepositoryCredential;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.ZoltVersionPin;
import sh.zolt.manifest.authored.AuthoredAlias;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredCommands;
import sh.zolt.manifest.authored.AuthoredCoverage;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredJavaTestToolchain;
import sh.zolt.manifest.authored.AuthoredJavaToolchain;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredRepositoryControl;
import sh.zolt.manifest.authored.AuthoredTask;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.manifest.authored.AuthoredVersionAliases;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.manifest.authored.AuthoredWorkspaceProjectDefaults;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.ToolchainPolicy;

final class EffectiveManifestComposerTest {
    private static final EffectiveManifestComposer COMPOSER = new EffectiveManifestComposer();

    @Test
    void composesSparseApplicationWithCanonicalBuiltInsAndProvenance() {
        AuthoredProjectIdentity authoredIdentity = applicationIdentity();
        var authored = new StandaloneManifestFixture().identity(authoredIdentity).create();

        EffectiveManifest effective = COMPOSER.composeStandalone(authored);

        assertSame(authored, effective.authored());
        assertTrue(effective.workspace().isEmpty());
        EffectiveProject project = effective.project();
        assertEquals(new ProjectName("app"), project.identity().name().value());
        assertSource(project.identity().name(), "project", "name");
        assertSource(project.identity().version(), "project", "version");
        assertSource(project.identity().group(), "project", "group");
        assertSource(project.identity().javaRelease().orElseThrow(), "project", "java");
        assertEquals(ValueOrigin.AUTHORED, project.identity().license().orElseThrow().origin());

        EffectiveToolchains toolchains = project.shared().toolchains();
        EffectiveJavaRuntime.System main = assertInstanceOf(
                EffectiveJavaRuntime.System.class, toolchains.mainJava().orElseThrow());
        assertSame(project.identity().javaRelease().orElseThrow(), main.requiredRelease());
        EffectiveTestJavaRuntime.SameAsMain test = assertInstanceOf(
                EffectiveTestJavaRuntime.SameAsMain.class, toolchains.testJava().orElseThrow());
        assertSame(main, test.main());

        EffectiveDependencyRepositories repositories = project.shared().repositories();
        assertEquals(ValueOrigin.BUILT_IN, repositories.central().origin());
        assertEquals(
                AuthoredDependencyRepositories.MAVEN_CENTRAL_URL,
                repositories.central().value().repository().orElseThrow().url());
        assertEquals(List.of(new LocalId("central")), repositories.lookupOrder().value());
        assertEquals(ValueOrigin.BUILT_IN, repositories.lookupOrder().origin());
        assertEquals(EffectiveCoverage.empty(), project.shared().coverage());
        assertEquals(EffectiveCommands.empty(), project.shared().commands());
        assertSame(authored.build().build(), project.local().build());
        assertSame(authored.packaging(), project.local().packaging());
    }

    @Test
    void composesAuthoredSharedDomainsAndManagedToolchainDefaults() {
        LocalId release = new LocalId("release");
        LocalId credentialId = new LocalId("company");
        LocalId repositoryId = new LocalId("internal");
        DependencyCoordinate platform = new DependencyCoordinate("com.example:platform");
        AuthoredVersionAliases versions = new AuthoredVersionAliases(
                Map.of(release, new VersionAliasValue("2.0.0")));
        AuthoredCredentials credentials = new AuthoredCredentials(Map.of(
                credentialId,
                new RepositoryCredential.BearerToken(new EnvironmentVariableName("TOKEN"))));
        AuthoredDependencyRepositories repositories = new AuthoredDependencyRepositories(
                Optional.of(new AuthoredRepositoryControl(
                        Optional.of(new CentralRepositoryControl.Disabled()),
                        Optional.of(List.of(repositoryId)))),
                Map.of(
                        repositoryId,
                        new DependencyRepository(
                                new RepositoryUrl("https://repo.example.test/maven"),
                                Optional.of(credentialId))));
        AuthoredToolchains toolchains = new AuthoredToolchains(
                Optional.of(new ZoltVersionPin("0.1.0")),
                Optional.of(new AuthoredJavaToolchain(
                        Optional.empty(),
                        Optional.of(JavaDistribution.GRAALVM_COMMUNITY),
                        Optional.of(Set.of(JavaFeature.NATIVE_IMAGE)),
                        Optional.empty())),
                Optional.of(new AuthoredJavaTestToolchain(
                        Optional.of(new JavaFeatureRelease(25)),
                        Optional.empty(),
                        Optional.empty())));
        AuthoredBuildConfiguration build = new AuthoredBuildConfiguration(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new AuthoredCoverage(
                        Optional.of(new CoveragePercentage(90)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())));
        BuiltInCommandCatalog builtIns = BuiltInCommandCatalog.fromStrings(List.of("build"));
        AuthoredCommands commands = new AuthoredCommands(
                Map.of(
                        new LocalId("audit"),
                        new AuthoredTask(
                                Optional.empty(), List.of("audit.sh"), Optional.empty(), Map.of())),
                Map.of(new LocalId("fast"), new AuthoredAlias(List.of("build", "--quick"))),
                builtIns);
        var authored = new StandaloneManifestFixture()
                .versions(versions)
                .credentials(credentials)
                .repositories(repositories)
                .platforms(new AuthoredPlatforms(Map.of(
                        platform, new PlatformSelector.VersionReference(release))))
                .toolchains(toolchains)
                .build(build)
                .commands(commands)
                .create();

        EffectiveSharedConfiguration shared = COMPOSER.composeStandalone(authored).project().shared();

        assertSource(shared.versions().get(release), "versions", "release");
        assertSource(shared.credentials().get(credentialId), "credentials", "company");
        assertSource(shared.platforms().get(platform), "platforms", "com.example:platform");
        assertFalse(shared.repositories().central().value().enabled());
        assertSource(shared.repositories().central(), "repositories", "central");
        assertSource(shared.repositories().named().get(repositoryId), "repositories", "internal");
        assertSource(shared.repositories().lookupOrder(), "repositories", "order");
        assertSource(shared.coverage().line().orElseThrow(), "coverage", "line");
        assertSource(shared.commands().tasks().get(new LocalId("audit")), "tasks", "audit");
        assertSource(shared.commands().aliases().get(new LocalId("fast")), "aliases", "fast");
        assertSource(shared.toolchains().zolt().orElseThrow(), "toolchain", "zolt", "version");

        EffectiveJavaRuntime.Requested main = assertInstanceOf(
                EffectiveJavaRuntime.Requested.class, shared.toolchains().mainJava().orElseThrow());
        assertEquals(21, main.version().value().value());
        assertSource(main.version(), "project", "java");
        assertEquals(JavaDistribution.GRAALVM_COMMUNITY, main.distribution().value());
        assertSource(main.distribution(), "toolchain", "java", "distribution");
        assertEquals(Set.of(JavaFeature.NATIVE_IMAGE), main.features().value());
        assertEquals(ValueOrigin.BUILT_IN, main.policy().origin());
        EffectiveTestJavaRuntime.Requested test = assertInstanceOf(
                EffectiveTestJavaRuntime.Requested.class,
                shared.toolchains().testJava().orElseThrow());
        assertEquals(25, test.version().value().value());
        assertSource(test.version(), "toolchain", "java", "test", "version");
        assertSame(main.distribution(), test.distribution());
        assertSame(main.policy(), test.policy());
    }

    @Test
    void derivesBuiltInRepositoryOrderFromSortedNamedIdsThenCentral() {
        AuthoredDependencyRepositories authoredRepositories = new AuthoredDependencyRepositories(
                Optional.empty(),
                Map.of(
                        new LocalId("zeta"),
                        DependencyRepository.unauthenticated(
                                new RepositoryUrl("https://zeta.example.test/maven")),
                        new LocalId("alpha"),
                        DependencyRepository.unauthenticated(
                                new RepositoryUrl("https://alpha.example.test/maven"))));

        EffectiveDependencyRepositories repositories = COMPOSER.composeStandalone(
                        new StandaloneManifestFixture()
                                .repositories(authoredRepositories)
                                .create())
                .project()
                .shared()
                .repositories();

        assertEquals(
                List.of(new LocalId("alpha"), new LocalId("zeta"), new LocalId("central")),
                repositories.lookupOrder().value());
        assertEquals(ValueOrigin.BUILT_IN, repositories.lookupOrder().origin());
    }

    @Test
    void retainsInheritedProjectJavaProvenanceForDerivedManagedVersion() {
        EffectiveProjectIdentity identity = new EffectiveProjectIdentityComposer().compose(
                new AuthoredProjectIdentity(
                        new ProjectName("member"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                "modules/member/zolt.toml",
                Optional.of(new EffectiveProjectIdentityComposer.WorkspaceDefaults(
                        new AuthoredWorkspaceProjectDefaults(
                                Optional.of(new ProjectGroup("com.example")),
                                Optional.of(new ProjectVersion("1.0.0")),
                                Optional.of(new JavaFeatureRelease(21)),
                                Optional.empty()),
                        "zolt.toml")),
                false);
        AuthoredToolchains authored = new AuthoredToolchains(
                Optional.empty(),
                Optional.of(new AuthoredJavaToolchain(
                        Optional.empty(),
                        Optional.of(JavaDistribution.TEMURIN),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty());

        EffectiveJavaRuntime.Requested main = assertInstanceOf(
                EffectiveJavaRuntime.Requested.class,
                new EffectiveToolchainsComposer()
                        .compose(authored, identity, "modules/member/zolt.toml", false)
                        .mainJava()
                        .orElseThrow());

        assertSame(identity.javaRelease().orElseThrow(), main.version());
        assertEquals(ValueOrigin.INHERITED, main.version().origin());
        assertEquals(
                new ManifestSource(
                        "zolt.toml", List.of("workspace", "project", "java")),
                main.version().source().orElseThrow());
    }

    @Test
    void rejectsWorkspaceDocumentsMissingStandaloneIdentityAndIncompatibleToolchains() {
        NullPointerException nullFailure = assertThrows(
                NullPointerException.class, () -> COMPOSER.composeStandalone(null));
        assertEquals("Authored manifest must not be null.", nullFailure.getMessage());

        AuthoredWorkspace workspace = new AuthoredWorkspace(
                new LocalId("platform"),
                new AuthoredWorkspaceMembers(
                        List.of(new WorkspaceMemberPattern("modules/*")),
                        List.of(),
                        Optional.empty()),
                Optional.empty());
        IllegalArgumentException workspaceFailure = assertThrows(
                IllegalArgumentException.class,
                () -> COMPOSER.composeStandalone(
                        new StandaloneManifestFixture().workspace(workspace).create()));
        assertTrue(workspaceFailure.getMessage().contains("does not accept a [workspace]"));

        List<AuthoredProjectIdentity> incomplete = List.of(
                identity(Optional.empty(), Optional.of(new ProjectGroup("com.example")), Optional.of(21)),
                identity(Optional.of(new ProjectVersion("1.0.0")), Optional.empty(), Optional.of(21)),
                identity(
                        Optional.of(new ProjectVersion("1.0.0")),
                        Optional.of(new ProjectGroup("com.example")),
                        Optional.empty()));
        incomplete.forEach(identity -> assertThrows(
                IllegalArgumentException.class,
                () -> COMPOSER.composeStandalone(
                        new StandaloneManifestFixture().identity(identity).create())));

        AuthoredToolchains tooOld = new AuthoredToolchains(
                Optional.empty(),
                Optional.of(new AuthoredJavaToolchain(
                        Optional.of(new JavaFeatureRelease(17)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(ToolchainPolicy.REQUIRE_MANAGED))),
                Optional.empty());
        IllegalArgumentException incompatible = assertThrows(
                IllegalArgumentException.class,
                () -> COMPOSER.composeStandalone(
                        new StandaloneManifestFixture().toolchains(tooOld).create()));
        assertTrue(incompatible.getMessage().contains("cannot execute project Java release 21"));

        AuthoredToolchains testTooOld = new AuthoredToolchains(
                Optional.empty(),
                Optional.of(new AuthoredJavaToolchain(
                        Optional.of(new JavaFeatureRelease(21)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(ToolchainPolicy.REQUIRE_MANAGED))),
                Optional.of(new AuthoredJavaTestToolchain(
                        Optional.of(new JavaFeatureRelease(17)),
                        Optional.empty(),
                        Optional.empty())));
        IllegalArgumentException testIncompatible = assertThrows(
                IllegalArgumentException.class,
                () -> COMPOSER.composeStandalone(
                        new StandaloneManifestFixture().toolchains(testTooOld).create()));
        assertEquals(
                "Effective test Java runtime release 17 cannot execute project Java release 21."
                        + " Set [toolchain.java.test].version to a Java feature release that can run"
                        + " classes compiled for [project].java, then run `zolt toolchain sync`.",
                testIncompatible.getMessage());

        AuthoredToolchains defaultOnly = new AuthoredToolchains(
                Optional.empty(),
                Optional.of(new AuthoredJavaToolchain(
                        Optional.of(new JavaFeatureRelease(21)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty());
        IllegalArgumentException defaultOnlyFailure = assertThrows(
                IllegalArgumentException.class,
                () -> COMPOSER.composeStandalone(
                        new StandaloneManifestFixture().toolchains(defaultOnly).create()));
        assertTrue(defaultOnlyFailure.getMessage().contains("or nondefault version"));
    }

    private static AuthoredProjectIdentity applicationIdentity() {
        return new AuthoredProjectIdentity(
                new ProjectName("app"),
                Optional.of(new ProjectVersion("1.0.0")),
                Optional.of(new ProjectGroup("com.example")),
                Optional.of(new JavaFeatureRelease(21)),
                Optional.of(new ProjectLicense.Identifier("Apache-2.0")));
    }

    private static AuthoredProjectIdentity identity(
            Optional<ProjectVersion> version,
            Optional<ProjectGroup> group,
            Optional<Integer> javaRelease) {
        return new AuthoredProjectIdentity(
                new ProjectName("app"),
                version,
                group,
                javaRelease.map(JavaFeatureRelease::new),
                Optional.empty());
    }

    private static void assertSource(EffectiveValue<?> value, String... fieldPath) {
        assertEquals(ValueOrigin.AUTHORED, value.origin());
        assertEquals(
                new ManifestSource("zolt.toml", List.of(fieldPath)),
                value.source().orElseThrow());
    }
}
