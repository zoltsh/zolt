package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.project.toolchain.JavaFeatureRelease;

final class AuthoredManifestValidationOrderTest {
    @Test
    void virtualRootRejectsGeneratedSourcesBeforeTests() {
        Fixture fixture = new Fixture();
        fixture.workspace = Optional.of(workspace());
        fixture.project = Optional.empty();
        fixture.build = testsOnly();
        fixture.generated = Optional.of(AuthoredGeneratedSources.empty());

        assertFailure(
                "A virtual workspace root cannot author project-only generated sources.",
                fixture);
    }

    @Test
    void standaloneBomRejectsMainToolchainBeforeTestToolchainDependenciesAndSources() {
        Fixture fixture = bomFixture();
        fixture.toolchains = new AuthoredToolchains(
                Optional.empty(),
                Optional.of(mainJavaToolchain()),
                Optional.of(testJavaToolchain()));
        fixture.dependencies = Optional.of(AuthoredDependencies.empty());
        fixture.build = compilableBuild();

        assertFailure("A BOM cannot author project-local main Java toolchain.", fixture);
    }

    @Test
    void standaloneBomRejectsTestToolchainBeforeDependenciesAndSources() {
        Fixture fixture = bomFixture();
        fixture.toolchains = new AuthoredToolchains(
                Optional.empty(), Optional.empty(), Optional.of(testJavaToolchain()));
        fixture.dependencies = Optional.of(AuthoredDependencies.empty());
        fixture.build = compilableBuild();

        assertFailure("A BOM cannot author project-local test Java toolchain.", fixture);
    }

    @Test
    void standaloneBomRejectsDependenciesBeforeCompilableSources() {
        Fixture fixture = bomFixture();
        fixture.dependencies = Optional.of(AuthoredDependencies.empty());
        fixture.build = compilableBuild();

        assertFailure("A BOM cannot author dependencies.", fixture);
    }

    @Test
    void bomRejectsGeneratedSourcesBeforeTests() {
        Fixture fixture = bomFixture();
        fixture.build = testsOnly();
        fixture.generated = Optional.of(nonemptyGeneratedSources());

        assertFailure("A BOM cannot author generated sources.", fixture);
    }

    private static void assertFailure(String message, Fixture fixture) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, fixture::create);
        assertEquals(message, failure.getMessage());
    }

    private static Fixture bomFixture() {
        Fixture fixture = new Fixture();
        fixture.packaging = new AuthoredPackaging(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new AuthoredBom(
                        Optional.empty(), Optional.of(Map.of()), Optional.empty())));
        return fixture;
    }

    private static AuthoredWorkspace workspace() {
        return new AuthoredWorkspace(
                new LocalId("root"),
                new AuthoredWorkspaceMembers(
                        List.of(new WorkspaceMemberPattern("modules/*")),
                        List.of(),
                        Optional.empty()),
                Optional.empty());
    }

    private static AuthoredProject project() {
        return new AuthoredProject(
                new AuthoredProjectIdentity(
                        new ProjectName("demo"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                AuthoredProjectMetadata.empty());
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

    private static AuthoredBuildConfiguration compilableBuild() {
        return new AuthoredBuildConfiguration(
                Optional.of(new AuthoredBuild(
                        List.of(new ManifestRelativePath("src/main/java")),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
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

    private static final class Fixture {
        private Optional<AuthoredWorkspace> workspace = Optional.empty();
        private Optional<AuthoredProject> project = Optional.of(project());
        private AuthoredToolchains toolchains = AuthoredToolchains.empty();
        private Optional<AuthoredDependencies> dependencies = Optional.empty();
        private AuthoredBuildConfiguration build = AuthoredBuildConfiguration.empty();
        private Optional<AuthoredGeneratedSources> generated = Optional.empty();
        private AuthoredPackaging packaging = AuthoredPackaging.empty();

        private AuthoredManifest create() {
            return new AuthoredManifest(
                    workspace,
                    project,
                    toolchains,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    dependencies,
                    Optional.empty(),
                    Optional.empty(),
                    build,
                    generated,
                    packaging,
                    Optional.empty(),
                    Optional.empty());
        }
    }
}
