package sh.zolt.manifest.effective;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.CentralRepositoryControl;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.GeneratedArtifactRequest;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.RepositoryCredential;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.CoveragePercentage;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredCoverage;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredGeneratedPresets;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
import sh.zolt.manifest.authored.AuthoredGeneratedTools;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredPublicationRepository;
import sh.zolt.manifest.authored.AuthoredPublishing;
import sh.zolt.manifest.authored.AuthoredRepositoryControl;
import sh.zolt.manifest.authored.AuthoredVersionAliases;
import sh.zolt.project.toolchain.JavaFeatureRelease;

final class StandaloneManifestIntegrityValidatorTest {
    private static final EffectiveManifestComposer COMPOSER = new EffectiveManifestComposer();
    private static final LocalId RELEASE = new LocalId("release");
    private static final LocalId MISSING = new LocalId("missing");
    private static final DependencyCoordinate LIBRARY =
            new DependencyCoordinate("com.example:library");
    private static final DependencyCoordinate PLATFORM =
            new DependencyCoordinate("com.example:platform");

    @Test
    void acceptsEveryStandaloneVersionAndCredentialReferenceSurface() {
        LocalId credential = new LocalId("company");
        AuthoredDependencyRepositories repositories = repositories(credential);
        AuthoredPublishing publishing = publishing(credential);
        AuthoredGeneratedSources generated = generated(new AuthoredGeneratedTool.Jvm(
                List.of(new GeneratedArtifactRequest(
                        LIBRARY, new DependencySelector.VersionReference(RELEASE))),
                new JavaBinaryClassName("com.example.Main")));
        var manifest = new StandaloneManifestFixture()
                .versions(versions())
                .credentials(new AuthoredCredentials(Map.of(
                        credential,
                        new RepositoryCredential.BearerToken(
                                new EnvironmentVariableName("REPOSITORY_TOKEN")))))
                .repositories(repositories)
                .platforms(new AuthoredPlatforms(
                        Map.of(PLATFORM, new PlatformSelector.VersionReference(RELEASE))))
                .dependencies(dependencies(new DependencySelector.VersionReference(RELEASE)))
                .constraints(constraints(RELEASE))
                .generated(generated)
                .publishing(publishing)
                .create();

        assertDoesNotThrow(() -> COMPOSER.composeStandalone(manifest));
    }

    @Test
    void rejectsUndefinedDependencyConstraintAndPlatformAliases() {
        var dependency = new StandaloneManifestFixture()
                .dependencies(dependencies(new DependencySelector.VersionReference(MISSING)))
                .create();
        var constraint = new StandaloneManifestFixture()
                .constraints(constraints(MISSING))
                .create();
        var platform = new StandaloneManifestFixture()
                .platforms(new AuthoredPlatforms(
                        Map.of(PLATFORM, new PlatformSelector.VersionReference(MISSING))))
                .create();

        assertUndefinedAlias(dependency, "Dependency `com.example:library`");
        assertUndefinedAlias(constraint, "Dependency constraint `com.example:library`");
        assertUndefinedAlias(platform, "Platform `com.example:platform`");
    }

    @Test
    void rejectsUndefinedAliasesForEveryGeneratedArtifactShape() {
        List<AuthoredGeneratedTool> tools = List.of(
                new AuthoredGeneratedTool.OpenApi(
                        Optional.empty(),
                        Optional.of(new DependencySelector.VersionReference(MISSING))),
                new AuthoredGeneratedTool.Protobuf(
                        Optional.empty(),
                        Optional.of(new DependencySelector.VersionReference(MISSING)),
                        Optional.empty(),
                        Optional.empty()),
                new AuthoredGeneratedTool.Protobuf(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new DependencySelector.VersionReference(MISSING))),
                new AuthoredGeneratedTool.Jvm(
                        List.of(new GeneratedArtifactRequest(
                                LIBRARY, new DependencySelector.VersionReference(MISSING))),
                        new JavaBinaryClassName("com.example.Main")));

        tools.forEach(tool -> {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> COMPOSER.composeStandalone(
                            new StandaloneManifestFixture().generated(generated(tool)).create()));
            assertTrue(failure.getMessage().startsWith("Generated tool `tool`"));
            assertTrue(failure.getMessage().endsWith(
                    "references undefined version alias `missing`."));
        });
    }

    @Test
    void rejectsUndefinedBomAliasesAndStandaloneMemberSelection() {
        AuthoredBom versionReference = new AuthoredBom(
                Optional.empty(),
                Optional.of(Map.of(
                        LIBRARY,
                        new AuthoredBom.Version(
                                new PlatformSelector.VersionReference(MISSING),
                                Optional.empty(),
                                Optional.empty()))),
                Optional.empty());
        AuthoredBom importReference = new AuthoredBom(
                Optional.empty(),
                Optional.empty(),
                Optional.of(Map.of(
                        PLATFORM, new PlatformSelector.VersionReference(MISSING))));
        AuthoredBom members = new AuthoredBom(
                Optional.of(new AuthoredBom.Members(
                        new AuthoredBom.AllMembers(), List.of())),
                Optional.empty(),
                Optional.empty());

        assertUndefinedAlias(bomManifest(versionReference), "BOM version `com.example:library`");
        assertUndefinedAlias(bomManifest(importReference), "BOM import `com.example:platform`");
        IllegalArgumentException memberFailure = assertThrows(
                IllegalArgumentException.class,
                () -> COMPOSER.composeStandalone(bomManifest(members)));
        assertEquals(
                "A standalone BOM cannot declare workspace members or exclusions.",
                memberFailure.getMessage());
    }

    /**
     * Design §12.6 bans tests on a BOM and gives it no compilable sources, so §10.10 coverage floors
     * have nothing to gate. The authored layer defers the decision until BOM-ness is known, so the
     * rejection lands at composition — and only for a BOM.
     */
    @Test
    void rejectsAuthoredCoverageFloorsOnAStandaloneBom() {
        AuthoredBom bom = new AuthoredBom(
                Optional.empty(), Optional.of(Map.of()), Optional.empty());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> COMPOSER.composeStandalone(bomManifest(bom, coverageOnly())));

        assertEquals(
                "An effective BOM cannot author coverage floors; a BOM has no compilable sources or"
                        + " tests to measure. Remove [coverage] from the BOM manifest and author it on"
                        + " the workspace root or on the members that run tests.",
                failure.getMessage());
        assertDoesNotThrow(() -> COMPOSER.composeStandalone(
                new StandaloneManifestFixture().build(coverageOnly()).create()));
    }

    private static AuthoredBuildConfiguration coverageOnly() {
        return new AuthoredBuildConfiguration(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new AuthoredCoverage(
                        Optional.of(new CoveragePercentage(88.0)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())));
    }

    @Test
    void composesStandaloneBomWithoutInventingJava() {
        AuthoredBom bom = new AuthoredBom(
                Optional.empty(), Optional.of(Map.of()), Optional.empty());

        EffectiveManifest effective = COMPOSER.composeStandalone(bomManifest(bom));

        assertTrue(effective.project().identity().javaRelease().isEmpty());
        assertTrue(effective.project().shared().toolchains().mainJava().isEmpty());
        assertTrue(effective.project().shared().toolchains().testJava().isEmpty());
    }

    @Test
    void rejectsWorkspaceSelectorsAndUnbackedManagedDependencies() {
        var workspace = new StandaloneManifestFixture()
                .dependencies(dependencies(new DependencySelector.Workspace()))
                .create();
        var managed = new StandaloneManifestFixture()
                .dependencies(dependencies(new DependencySelector.Managed()))
                .create();

        IllegalArgumentException workspaceFailure = assertThrows(
                IllegalArgumentException.class,
                () -> COMPOSER.composeStandalone(workspace));
        IllegalArgumentException managedFailure = assertThrows(
                IllegalArgumentException.class,
                () -> COMPOSER.composeStandalone(managed));

        assertTrue(workspaceFailure.getMessage().contains("cannot use `workspace = true`"));
        assertTrue(managedFailure.getMessage().contains("no [platforms] entry is available"));
    }

    @Test
    void rejectsUndefinedDependencyAndPublicationCredentials() {
        var dependencyRepository = new StandaloneManifestFixture()
                .repositories(repositories(MISSING))
                .create();
        var publicationRepository = new StandaloneManifestFixture()
                .publishing(publishing(MISSING))
                .create();

        IllegalArgumentException dependencyFailure = assertThrows(
                IllegalArgumentException.class,
                () -> COMPOSER.composeStandalone(dependencyRepository));
        IllegalArgumentException publicationFailure = assertThrows(
                IllegalArgumentException.class,
                () -> COMPOSER.composeStandalone(publicationRepository));

        assertEquals(
                "Dependency repository references undefined credential `missing`.",
                dependencyFailure.getMessage());
        assertEquals(
                "Publication repository references undefined credential `missing`.",
                publicationFailure.getMessage());
    }

    private static AuthoredVersionAliases versions() {
        return new AuthoredVersionAliases(
                Map.of(RELEASE, new VersionAliasValue("1.0.0")));
    }

    private static AuthoredDependencies dependencies(DependencySelector selector) {
        return new AuthoredDependencies(List.of(new AuthoredDependency(
                DependencyLane.IMPLEMENTATION,
                LIBRARY,
                selector,
                AuthoredDependencyMetadata.none())));
    }

    private static AuthoredDependencyConstraints constraints(LocalId alias) {
        return new AuthoredDependencyConstraints(Map.of(
                LIBRARY,
                new AuthoredDependencyConstraint(
                        new DependencyConstraintSelector.VersionReference(alias),
                        Optional.empty())));
    }

    private static AuthoredGeneratedSources generated(AuthoredGeneratedTool tool) {
        return new AuthoredGeneratedSources(
                new AuthoredGeneratedTools(Map.of(new LocalId("tool"), tool)),
                AuthoredGeneratedPresets.empty(),
                Map.of(),
                Map.of());
    }

    private static AuthoredDependencyRepositories repositories(LocalId credential) {
        return new AuthoredDependencyRepositories(
                Optional.of(new AuthoredRepositoryControl(
                        Optional.of(new CentralRepositoryControl.Disabled()),
                        Optional.of(List.of(new LocalId("internal"))))),
                Map.of(
                        new LocalId("internal"),
                        new DependencyRepository(
                                new RepositoryUrl("https://repo.example.test/maven"),
                                Optional.of(credential))));
    }

    private static AuthoredPublishing publishing(LocalId credential) {
        return new AuthoredPublishing(
                Optional.empty(),
                Map.of(
                        new LocalId("internal"),
                        new AuthoredPublicationRepository(
                                new RepositoryUrl("https://publish.example.test/maven"),
                                Optional.of(credential))),
                Optional.empty(),
                Optional.empty());
    }

    private static AuthoredManifest bomManifest(AuthoredBom bom) {
        return bomManifest(bom, null);
    }

    private static AuthoredManifest bomManifest(AuthoredBom bom, AuthoredBuildConfiguration build) {
        AuthoredProjectIdentity identity = new AuthoredProjectIdentity(
                new ProjectName("catalog"),
                Optional.of(new ProjectVersion("1.0.0")),
                Optional.of(new ProjectGroup("com.example")),
                Optional.empty(),
                Optional.empty());
        AuthoredPackaging packaging = new AuthoredPackaging(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(bom));
        StandaloneManifestFixture fixture = new StandaloneManifestFixture()
                .identity(identity)
                .packaging(packaging);
        if (build != null) {
            fixture = fixture.build(build);
        }
        return fixture.create();
    }

    private static void assertUndefinedAlias(
            AuthoredManifest manifest,
            String subject) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> COMPOSER.composeStandalone(manifest));
        assertEquals(subject + " references undefined version alias `missing`.", failure.getMessage());
    }
}
