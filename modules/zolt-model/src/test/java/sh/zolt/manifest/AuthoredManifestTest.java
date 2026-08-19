package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import sh.zolt.project.toolchain.JavaFeatureRelease;

final class AuthoredManifestTest {
    private static final BuiltInCommandCatalog BUILT_INS =
            BuiltInCommandCatalog.fromStrings(List.of("build", "check", "task"));

    @Test
    void requiresAWorkspaceOrProjectWithoutMaterializingEffectiveIdentityDefaults() {
        Fixture emptyDocument = new Fixture();
        emptyDocument.project = Optional.empty();

        assertThrows(IllegalArgumentException.class, emptyDocument::create);

        AuthoredManifest standalone = new Fixture().create();
        assertTrue(standalone.workspace().isEmpty());
        assertTrue(standalone.project().orElseThrow().identity().version().isEmpty());
        assertTrue(standalone.project().orElseThrow().identity().group().isEmpty());
        assertTrue(standalone.project().orElseThrow().identity().javaRelease().isEmpty());
    }

    @Test
    void preservesExplicitCollectionDomainsSeparatelyFromOmission() {
        Fixture fixture = new Fixture();
        fixture.toolchains = new AuthoredToolchains(
                Optional.of(new ZoltVersionPin("0.1.0")), Optional.empty(), Optional.empty());
        fixture.versions = Optional.of(AuthoredVersionAliases.empty());
        fixture.repositories = Optional.of(new AuthoredDependencyRepositories(
                Optional.empty(),
                Map.of(
                        new LocalId("company"),
                        DependencyRepository.unauthenticated(
                                new RepositoryUrl("https://repo.example.test/maven")))));
        fixture.credentials = Optional.of(AuthoredCredentials.empty());
        fixture.platforms = Optional.of(AuthoredPlatforms.empty());
        fixture.dependencies = Optional.of(AuthoredDependencies.empty());
        fixture.dependencyConstraints = Optional.of(AuthoredDependencyConstraints.empty());
        fixture.dependencyPolicy = Optional.of(dependencyPolicy());
        fixture.generated = Optional.of(AuthoredGeneratedSources.empty());
        fixture.packaging = packageWithExplicitFalse();
        fixture.publishing = Optional.of(AuthoredPublishing.empty());
        fixture.commands = Optional.of(AuthoredCommands.empty(BUILT_INS));

        AuthoredManifest manifest = fixture.create();

        assertEquals(fixture.toolchains, manifest.toolchains());
        assertEquals(fixture.versions, manifest.versions());
        assertEquals(fixture.repositories, manifest.repositories());
        assertEquals(fixture.credentials, manifest.credentials());
        assertEquals(fixture.platforms, manifest.platforms());
        assertEquals(fixture.dependencies, manifest.dependencies());
        assertEquals(fixture.dependencyConstraints, manifest.dependencyConstraints());
        assertEquals(fixture.dependencyPolicy, manifest.dependencyPolicy());
        assertEquals(fixture.generated, manifest.generated());
        assertEquals(fixture.packaging, manifest.packaging());
        assertEquals(fixture.publishing, manifest.publishing());
        assertEquals(fixture.commands, manifest.commands());
    }

    @Test
    void virtualWorkspaceAllowsOnlyClosedSharedRootDomains() {
        Fixture fixture = virtualWorkspaceFixture();
        fixture.toolchains = sharedJavaToolchains();
        fixture.versions = Optional.of(AuthoredVersionAliases.empty());
        fixture.repositories = Optional.of(new AuthoredDependencyRepositories(
                Optional.empty(),
                Map.of(
                        new LocalId("company"),
                        DependencyRepository.unauthenticated(
                                new RepositoryUrl("https://repo.example.test/maven")))));
        fixture.credentials = Optional.of(AuthoredCredentials.empty());
        fixture.platforms = Optional.of(platforms());
        fixture.buildConfiguration = coverageOnly();
        fixture.commands = Optional.of(AuthoredCommands.empty(BUILT_INS));

        AuthoredManifest manifest = fixture.create();

        assertTrue(manifest.project().isEmpty());
        assertEquals(fixture.toolchains, manifest.toolchains());
        assertEquals(fixture.platforms, manifest.platforms());
        assertEquals(fixture.buildConfiguration.coverage(), manifest.build().coverage());
        assertEquals(fixture.commands, manifest.commands());
    }

    @Test
    void rejectsEveryProjectOnlyDomainInAVirtualWorkspaceRoot() {
        List<Consumer<Fixture>> projectDomains = List.of(
                fixture -> fixture.dependencies = Optional.of(AuthoredDependencies.empty()),
                fixture -> fixture.dependencyConstraints =
                        Optional.of(AuthoredDependencyConstraints.empty()),
                fixture -> fixture.dependencyPolicy = Optional.of(dependencyPolicy()),
                fixture -> fixture.buildConfiguration = buildOnly(outputBuild()),
                fixture -> fixture.buildConfiguration = compilerOnly(),
                fixture -> fixture.buildConfiguration = resourcesOnly(),
                fixture -> fixture.buildConfiguration = testsOnly(),
                fixture -> fixture.generated = Optional.of(AuthoredGeneratedSources.empty()),
                fixture -> fixture.packaging = packageWithExplicitFalse(),
                fixture -> fixture.publishing = Optional.of(AuthoredPublishing.empty()));

        for (Consumer<Fixture> projectDomain : projectDomains) {
            Fixture fixture = virtualWorkspaceFixture();
            projectDomain.accept(fixture);
            assertThrows(IllegalArgumentException.class, fixture::create);
        }
    }

    @Test
    void rejectsEverySourceLocalBomBuildOrJavaDomain() {
        List<Consumer<Fixture>> prohibitedDomains = List.of(
                fixture -> fixture.project = Optional.of(project(
                        Optional.of(new JavaFeatureRelease(21)), Optional.empty())),
                fixture -> fixture.project = Optional.of(project(
                        Optional.empty(), Optional.of(new JavaBinaryClassName("com.example.Main")))),
                fixture -> fixture.buildConfiguration = buildOnly(new AuthoredBuild(
                        List.of(new ManifestRelativePath("src/main/java")),
                        Optional.empty(),
                        Optional.empty())),
                fixture -> fixture.dependencies = Optional.of(AuthoredDependencies.empty()),
                fixture -> fixture.dependencyConstraints =
                        Optional.of(AuthoredDependencyConstraints.empty()),
                fixture -> fixture.dependencyPolicy = Optional.of(dependencyPolicy()),
                fixture -> fixture.buildConfiguration = compilerOnly(),
                fixture -> fixture.buildConfiguration = resourcesOnly(),
                fixture -> fixture.buildConfiguration = testsOnly(),
                fixture -> fixture.generated = Optional.of(nonemptyGeneratedSources()),
                fixture -> fixture.toolchains = new AuthoredToolchains(
                        Optional.empty(), Optional.of(mainJavaToolchain()), Optional.empty()),
                fixture -> fixture.toolchains = new AuthoredToolchains(
                        Optional.empty(), Optional.empty(), Optional.of(testJavaToolchain())));

        for (Consumer<Fixture> prohibitedDomain : prohibitedDomains) {
            Fixture fixture = bomFixture();
            prohibitedDomain.accept(fixture);
            assertThrows(IllegalArgumentException.class, fixture::create);
        }
    }

    @Test
    void defersBomPlatformAndCoverageContextUntilWorkspaceComposition() {
        Fixture fixture = bomFixture();
        fixture.platforms = Optional.of(platforms());
        fixture.buildConfiguration = coverageOnly();

        AuthoredManifest manifest = fixture.create();

        assertEquals(fixture.platforms, manifest.platforms());
        assertEquals(fixture.buildConfiguration.coverage(), manifest.build().coverage());
    }

    @Test
    void bomRetainsOutputMetadataPublicationAndWorkspaceSharedDomains() {
        Fixture fixture = bomFixture();
        fixture.workspace = Optional.of(workspace());
        fixture.toolchains = sharedJavaToolchains();
        fixture.platforms = Optional.of(platforms());
        fixture.buildConfiguration = new AuthoredBuildConfiguration(
                Optional.of(new AuthoredBuild(
                        List.of(),
                        Optional.of(output()),
                        Optional.of(new AuthoredBuild.Metadata(
                                Optional.of(true), Optional.of(false), Optional.of(true))))),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                coverageOnly().coverage());
        fixture.generated = Optional.of(AuthoredGeneratedSources.empty());
        fixture.publishing = Optional.of(AuthoredPublishing.empty());
        fixture.commands = Optional.of(AuthoredCommands.empty(BUILT_INS));

        AuthoredManifest manifest = fixture.create();

        assertEquals(fixture.toolchains, manifest.toolchains());
        assertEquals(fixture.platforms, manifest.platforms());
        assertEquals(fixture.buildConfiguration, manifest.build());
        assertEquals(fixture.publishing, manifest.publishing());
        assertTrue(manifest.packaging().bom().isPresent());
    }

    @Test
    void defersBomMembershipAliasAndRequiredIdentityChecksToWorkspaceComposition() {
        Fixture fixture = new Fixture();
        fixture.packaging = bomPackaging(new AuthoredBom(
                Optional.of(new AuthoredBom.Members(
                        new AuthoredBom.AllMembers(), List.of(new WorkspaceMemberPath("apps/admin")))),
                Optional.empty(),
                Optional.of(Map.of(
                        new DependencyCoordinate("com.example:parent-bom"),
                        new PlatformSelector.VersionReference(new LocalId("missing-alias"))))));

        AuthoredManifest manifest = fixture.create();

        assertTrue(manifest.workspace().isEmpty());
        assertTrue(manifest.project().orElseThrow().identity().version().isEmpty());
        assertTrue(manifest.project().orElseThrow().identity().group().isEmpty());
        assertTrue(manifest.packaging().bom().orElseThrow().members().isPresent());
    }

    private static Fixture virtualWorkspaceFixture() {
        Fixture fixture = new Fixture();
        fixture.workspace = Optional.of(workspace());
        fixture.project = Optional.empty();
        return fixture;
    }

    private static Fixture bomFixture() {
        Fixture fixture = new Fixture();
        fixture.packaging = bomPackaging(new AuthoredBom(
                Optional.empty(), Optional.of(Map.of()), Optional.empty()));
        return fixture;
    }

    private static AuthoredWorkspace workspace() {
        return new AuthoredWorkspace(
                new LocalId("example"),
                new AuthoredWorkspaceMembers(
                        List.of("modules/*"), List.of(), Optional.empty()),
                Optional.of(new AuthoredWorkspaceProjectDefaults(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new JavaFeatureRelease(21)),
                        Optional.empty())));
    }

    private static AuthoredProject project(
            Optional<JavaFeatureRelease> javaRelease,
            Optional<JavaBinaryClassName> main) {
        return new AuthoredProject(
                new AuthoredProjectIdentity(
                        new ProjectName("demo"),
                        Optional.empty(),
                        Optional.empty(),
                        javaRelease,
                        Optional.empty()),
                new AuthoredProjectMetadata(
                        main,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Map.of()));
    }

    private static AuthoredDependencyPolicy dependencyPolicy() {
        return new AuthoredDependencyPolicy(
                Optional.of(DependencyConflictPolicy.RESOLVE),
                List.of(),
                Optional.empty(),
                Map.of());
    }

    private static AuthoredPlatforms platforms() {
        return new AuthoredPlatforms(Map.of(
                new DependencyCoordinate("com.example:platform"),
                new PlatformSelector.FixedVersion("1.0.0")));
    }

    private static AuthoredBuild outputBuild() {
        return new AuthoredBuild(List.of(), Optional.of(output()), Optional.empty());
    }

    private static AuthoredBuild.Output output() {
        return new AuthoredBuild.Output(
                Optional.of(new ManifestRelativePath("target")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static AuthoredBuildConfiguration buildOnly(AuthoredBuild build) {
        return new AuthoredBuildConfiguration(
                Optional.of(build),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static AuthoredBuildConfiguration compilerOnly() {
        return new AuthoredBuildConfiguration(
                Optional.empty(),
                Optional.of(new AuthoredCompiler(
                        Optional.of("UTF-8"),
                        Optional.empty(),
                        List.of(),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static AuthoredBuildConfiguration resourcesOnly() {
        return new AuthoredBuildConfiguration(
                Optional.empty(),
                Optional.empty(),
                Optional.of(AuthoredResources.empty()),
                Optional.empty(),
                Optional.empty());
    }

    private static AuthoredBuildConfiguration testsOnly() {
        return new AuthoredBuildConfiguration(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(AuthoredTests.empty()),
                Optional.empty());
    }

    private static AuthoredBuildConfiguration coverageOnly() {
        return new AuthoredBuildConfiguration(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new AuthoredCoverage(
                        Optional.of(new CoveragePercentage(80.0)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())));
    }

    private static AuthoredGeneratedSources nonemptyGeneratedSources() {
        return new AuthoredGeneratedSources(
                new AuthoredGeneratedTools(Map.of(
                        new LocalId("openapi"),
                        new AuthoredGeneratedTool.OpenApi(
                                Optional.empty(), Optional.empty()))),
                AuthoredGeneratedPresets.empty(),
                Map.of(),
                Map.of());
    }

    private static AuthoredToolchains sharedJavaToolchains() {
        return new AuthoredToolchains(
                Optional.of(new ZoltVersionPin("0.1.0")),
                Optional.of(mainJavaToolchain()),
                Optional.of(testJavaToolchain()));
    }

    private static AuthoredJavaToolchain mainJavaToolchain() {
        return new AuthoredJavaToolchain(
                Optional.of(new JavaFeatureRelease(21)),
                Optional.empty(),
                Optional.of(Set.of()),
                Optional.empty());
    }

    private static AuthoredJavaTestToolchain testJavaToolchain() {
        return new AuthoredJavaTestToolchain(
                Optional.of(new JavaFeatureRelease(21)),
                Optional.empty(),
                Optional.empty());
    }

    private static AuthoredPackaging packageWithExplicitFalse() {
        return new AuthoredPackaging(
                Optional.of(new AuthoredPackage(
                        Optional.empty(),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static AuthoredPackaging bomPackaging(AuthoredBom bom) {
        return new AuthoredPackaging(
                Optional.of(new AuthoredPackage(
                        Optional.empty(),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.empty())),
                Optional.of(new AuthoredPackageManifest(Map.of())),
                Optional.empty(),
                Optional.empty(),
                Optional.of(bom));
    }

    private static final class Fixture {
        private Optional<AuthoredWorkspace> workspace = Optional.empty();
        private Optional<AuthoredProject> project = Optional.of(project(
                Optional.empty(), Optional.empty()));
        private AuthoredToolchains toolchains = AuthoredToolchains.empty();
        private Optional<AuthoredVersionAliases> versions = Optional.empty();
        private Optional<AuthoredDependencyRepositories> repositories = Optional.empty();
        private Optional<AuthoredCredentials> credentials = Optional.empty();
        private Optional<AuthoredPlatforms> platforms = Optional.empty();
        private Optional<AuthoredDependencies> dependencies = Optional.empty();
        private Optional<AuthoredDependencyConstraints> dependencyConstraints = Optional.empty();
        private Optional<AuthoredDependencyPolicy> dependencyPolicy = Optional.empty();
        private AuthoredBuildConfiguration buildConfiguration = AuthoredBuildConfiguration.empty();
        private Optional<AuthoredGeneratedSources> generated = Optional.empty();
        private AuthoredPackaging packaging = AuthoredPackaging.empty();
        private Optional<AuthoredPublishing> publishing = Optional.empty();
        private Optional<AuthoredCommands> commands = Optional.empty();

        private AuthoredManifest create() {
            return new AuthoredManifest(
                    workspace,
                    project,
                    toolchains,
                    versions,
                    repositories,
                    credentials,
                    platforms,
                    dependencies,
                    dependencyConstraints,
                    dependencyPolicy,
                    buildConfiguration,
                    generated,
                    packaging,
                    publishing,
                    commands);
        }
    }
}
