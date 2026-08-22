package sh.zolt.manifest.effective;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestSource;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.RepositoryCredential;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredVersionAliases;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.manifest.authored.AuthoredWorkspaceProjectDefaults;
import sh.zolt.project.toolchain.JavaFeatureRelease;

final class EffectiveWorkspaceComposerTest {
    private static final EffectiveManifestComposer COMPOSER = new EffectiveManifestComposer();
    private static final WorkspaceMemberPath CORE = new WorkspaceMemberPath("modules/core");
    private static final WorkspaceMemberPath ROOT = new WorkspaceMemberPath(".");

    @Test
    void composesCompleteVirtualWorkspaceWithInheritedDefaultsAndMergedNamedMaps() {
        LocalId rootVersion = new LocalId("release");
        LocalId memberVersion = new LocalId("preview");
        LocalId credential = new LocalId("company");
        LocalId memberCredential = new LocalId("staging");
        LocalId repository = new LocalId("internal");
        DependencyCoordinate platform = new DependencyCoordinate("com.example:platform");
        DependencyCoordinate memberPlatform =
                new DependencyCoordinate("com.example:preview-platform");
        AuthoredManifest root = new WorkspaceManifestFixture()
                .virtualRoot(workspace(List.of("modules/*"), Optional.empty()))
                .versions(new AuthoredVersionAliases(
                        Map.of(rootVersion, new VersionAliasValue("1.0.0"))))
                .credentials(new AuthoredCredentials(Map.of(
                        credential,
                        new RepositoryCredential.BearerToken(
                                new EnvironmentVariableName("REPOSITORY_TOKEN")))))
                .repositories(new AuthoredDependencyRepositories(
                        Optional.empty(),
                        Map.of(
                                repository,
                                DependencyRepository.unauthenticated(
                                        new RepositoryUrl("https://repo.example.test/maven")))))
                .platforms(new AuthoredPlatforms(Map.of(
                        platform, new PlatformSelector.VersionReference(rootVersion))))
                .create();
        AuthoredManifest member = new WorkspaceManifestFixture()
                .identity(WorkspaceManifestFixture.sparseIdentity("core"))
                .versions(new AuthoredVersionAliases(
                        Map.of(memberVersion, new VersionAliasValue("2.0.0-rc.1"))))
                .credentials(new AuthoredCredentials(Map.of(
                        memberCredential,
                        new RepositoryCredential.BearerToken(
                                new EnvironmentVariableName("STAGING_TOKEN")))))
                .platforms(new AuthoredPlatforms(Map.of(
                        memberPlatform, new PlatformSelector.FixedVersion("2.0.0-rc.1"))))
                .create();

        EffectiveWorkspace effective = COMPOSER.composeWorkspace(root, Map.of(CORE, member));

        assertSame(root, effective.root());
        assertSame(root.workspace().orElseThrow(), effective.workspace());
        assertEquals(List.of(CORE), List.copyOf(effective.members().keySet()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> effective.members().put(CORE, effective.members().get(CORE)));
        EffectiveManifest result = effective.members().get(CORE);
        assertSame(member, result.authored());
        assertEquals(CORE, result.workspace().orElseThrow().memberPath());
        assertEquals(ValueOrigin.INHERITED, result.workspace().orElseThrow().name().origin());
        assertInherited(result.project().identity().version(), "workspace", "project", "version");
        assertInherited(result.project().identity().group(), "workspace", "project", "group");
        assertInherited(
                result.project().identity().javaRelease().orElseThrow(),
                "workspace", "project", "java");
        assertInherited(
                result.project().identity().license().orElseThrow(),
                "workspace", "project", "license");
        assertInherited(result.project().shared().versions().get(rootVersion), "versions", "release");
        assertEquals(
                ValueOrigin.AUTHORED,
                result.project().shared().versions().get(memberVersion).origin());
        assertInherited(
                result.project().shared().credentials().get(credential),
                "credentials", "company");
        assertEquals(
                ValueOrigin.AUTHORED,
                result.project().shared().credentials().get(memberCredential).origin());
        assertInherited(
                result.project().shared().platforms().get(platform),
                "platforms", "com.example:platform");
        assertEquals(
                ValueOrigin.AUTHORED,
                result.project().shared().platforms().get(memberPlatform).origin());
        assertInherited(
                result.project().shared().repositories().named().get(repository),
                "repositories", "internal");
    }

    @Test
    void reusesTheRootDocumentForTheDotMemberWithoutSelfRedeclaration() {
        AuthoredManifest root = new WorkspaceManifestFixture()
                .workspace(workspace(List.of("."), Optional.of(List.of(ROOT))))
                .identity(WorkspaceManifestFixture.sparseIdentity("root-project"))
                .versions(new AuthoredVersionAliases(Map.of(
                        new LocalId("release"), new VersionAliasValue("1.0.0"))))
                .create();

        EffectiveManifest dot = COMPOSER.composeWorkspace(root, Map.of(ROOT, root))
                .members()
                .get(ROOT);

        assertSame(root, dot.authored());
        assertEquals(ValueOrigin.AUTHORED, dot.workspace().orElseThrow().name().origin());
        assertEquals(
                ValueOrigin.INHERITED,
                dot.project().identity().version().origin());
        assertEquals(
                ValueOrigin.AUTHORED,
                dot.project().shared().versions().get(new LocalId("release")).origin());
    }

    @Test
    void validatesFinalMembershipShapeAndRootReuse() {
        AuthoredWorkspace workspace = workspace(List.of("modules/*"), Optional.empty());
        AuthoredManifest root = new WorkspaceManifestFixture().virtualRoot(workspace).create();
        AuthoredManifest member = new WorkspaceManifestFixture().create();

        assertMessage(
                () -> COMPOSER.composeWorkspace(
                        new WorkspaceManifestFixture().create(), Map.of(CORE, member)),
                "requires a [workspace]");
        assertMessage(
                () -> COMPOSER.composeWorkspace(root, Map.of()),
                "at least one final member");
        AuthoredManifest defaultRoot = new WorkspaceManifestFixture()
                .virtualRoot(workspace(
                        List.of("modules/*"),
                        Optional.of(List.of(new WorkspaceMemberPath("modules/missing")))))
                .create();
        assertMessage(
                () -> COMPOSER.composeWorkspace(defaultRoot, Map.of(CORE, member)),
                "is not in the final member set");

        AuthoredManifest dotRoot = new WorkspaceManifestFixture()
                .workspace(workspace(List.of("."), Optional.empty()))
                .create();
        AuthoredManifest equalButDistinct = new WorkspaceManifestFixture()
                .workspace(workspace(List.of("."), Optional.empty()))
                .create();
        assertMessage(
                () -> COMPOSER.composeWorkspace(dotRoot, Map.of(ROOT, equalButDistinct)),
                "must reuse the authored workspace root instance");

        AuthoredManifest nested = new WorkspaceManifestFixture().workspace(workspace).create();
        assertMessage(
                () -> COMPOSER.composeWorkspace(root, Map.of(CORE, nested)),
                "cannot declare a nested [workspace]");

        // The "requires a [project] domain" refusals name what is missing and where, so an adopter
        // never has to guess which manifest the aggregate composition rejected.
        AuthoredManifest virtualDotRoot = new WorkspaceManifestFixture()
                .virtualRoot(workspace(List.of("."), Optional.empty()))
                .create();
        assertMessage(
                () -> COMPOSER.composeWorkspace(virtualDotRoot, Map.of(ROOT, virtualDotRoot)),
                "The `.` workspace member requires a root [project] domain.");
        assertMessage(
                () -> COMPOSER.composeStandalone(virtualDotRoot),
                "Standalone effective composition does not accept a [workspace] domain.");
    }

    @Test
    void rejectsRootOwnedRedeclarationsAndMemberOwnedRepositoryOrZoltPolicy() {
        LocalId release = new LocalId("release");
        LocalId credential = new LocalId("company");
        DependencyCoordinate platform = new DependencyCoordinate("com.example:platform");
        RepositoryCredential credentialValue = new RepositoryCredential.BearerToken(
                new EnvironmentVariableName("REPOSITORY_TOKEN"));
        PlatformSelector platformValue = new PlatformSelector.FixedVersion("1.0.0");
        AuthoredManifest root = new WorkspaceManifestFixture()
                .virtualRoot(workspace(List.of("modules/*"), Optional.empty()))
                .versions(new AuthoredVersionAliases(
                        Map.of(release, new VersionAliasValue("1.0.0"))))
                .credentials(new AuthoredCredentials(Map.of(credential, credentialValue)))
                .platforms(new AuthoredPlatforms(Map.of(platform, platformValue)))
                .create();
        AuthoredManifest duplicate = new WorkspaceManifestFixture()
                .versions(new AuthoredVersionAliases(
                        Map.of(release, new VersionAliasValue("1.0.0"))))
                .create();
        assertMessage(
                () -> COMPOSER.composeWorkspace(root, Map.of(CORE, duplicate)),
                "cannot be redeclared");

        AuthoredManifest duplicateCredential = new WorkspaceManifestFixture()
                .credentials(new AuthoredCredentials(Map.of(credential, credentialValue)))
                .create();
        assertMessage(
                () -> COMPOSER.composeWorkspace(root, Map.of(CORE, duplicateCredential)),
                "cannot be redeclared");

        AuthoredManifest duplicatePlatform = new WorkspaceManifestFixture()
                .platforms(new AuthoredPlatforms(Map.of(platform, platformValue)))
                .create();
        assertMessage(
                () -> COMPOSER.composeWorkspace(root, Map.of(CORE, duplicatePlatform)),
                "cannot be redeclared");

        AuthoredManifest repositories = new WorkspaceManifestFixture()
                .repositories(AuthoredDependencyRepositories.defaults())
                .create();
        assertMessage(
                () -> COMPOSER.composeWorkspace(root, Map.of(CORE, repositories)),
                "repository universe is authoritative");

        AuthoredManifest zolt = new WorkspaceManifestFixture()
                .toolchains(new sh.zolt.manifest.authored.AuthoredToolchains(
                        Optional.of(new sh.zolt.manifest.ZoltVersionPin("0.1.0")),
                        Optional.empty(),
                        Optional.empty()))
                .create();
        assertMessage(
                () -> COMPOSER.composeWorkspace(root, Map.of(CORE, zolt)),
                "workspace root pin is authoritative");
    }

    @Test
    void rejectsDuplicateEffectiveGroupAndNameAfterInheritance() {
        AuthoredManifest root = new WorkspaceManifestFixture()
                .virtualRoot(workspace(List.of("modules/*"), Optional.empty()))
                .create();
        AuthoredManifest first = new WorkspaceManifestFixture()
                .identity(WorkspaceManifestFixture.sparseIdentity("core"))
                .create();
        AuthoredManifest second = new WorkspaceManifestFixture()
                .identity(WorkspaceManifestFixture.sparseIdentity("core"))
                .create();

        assertMessage(
                () -> COMPOSER.composeWorkspace(
                        root,
                        Map.of(
                                new WorkspaceMemberPath("modules/a"), first,
                                new WorkspaceMemberPath("modules/b"), second)),
                "duplicate effective project identity `com.example:core`");
    }

    @Test
    void validatesSharedReferencesAndResolvesWorkspaceTargetsInTheGraphPass() {
        LocalId release = new LocalId("release");
        AuthoredManifest root = new WorkspaceManifestFixture()
                .virtualRoot(workspace(List.of("modules/*"), Optional.empty()))
                .versions(new AuthoredVersionAliases(
                        Map.of(release, new VersionAliasValue("1.0.0"))))
                .create();
        AuthoredDependencies dependencies = new AuthoredDependencies(List.of(
                new AuthoredDependency(
                        DependencyLane.IMPLEMENTATION,
                        new DependencyCoordinate("com.external:library"),
                        new DependencySelector.VersionReference(release),
                        AuthoredDependencyMetadata.none()),
                new AuthoredDependency(
                        DependencyLane.TEST,
                        new DependencyCoordinate("com.example:sibling"),
                        new DependencySelector.Workspace(),
                        AuthoredDependencyMetadata.none())));
        AuthoredManifest member = new WorkspaceManifestFixture()
                .identity(WorkspaceManifestFixture.sparseIdentity("core"))
                .dependencies(dependencies)
                .create();
        WorkspaceMemberPath siblingPath = new WorkspaceMemberPath("modules/sibling");
        AuthoredManifest sibling = new WorkspaceManifestFixture()
                .identity(WorkspaceManifestFixture.sparseIdentity("sibling"))
                .create();

        EffectiveWorkspace workspace = COMPOSER.composeWorkspace(
                root, Map.of(CORE, member, siblingPath, sibling));
        EffectiveManifest effective = workspace.members().get(CORE);

        assertSame(
                member.dependencies(),
                effective.project().local().dependencies());
        assertEquals(siblingPath, workspace.graph()
                .workspaceDependencies()
                .getFirst()
                .provider());

        AuthoredManifest undefined = new WorkspaceManifestFixture()
                .identity(WorkspaceManifestFixture.sparseIdentity("undefined"))
                .dependencies(new AuthoredDependencies(List.of(new AuthoredDependency(
                        DependencyLane.IMPLEMENTATION,
                        new DependencyCoordinate("com.external:missing"),
                        new DependencySelector.VersionReference(new LocalId("missing")),
                        AuthoredDependencyMetadata.none()))))
                .create();
        assertMessage(
                () -> COMPOSER.composeWorkspace(root, Map.of(CORE, undefined)),
                "references undefined version alias `missing`");
    }

    private static AuthoredWorkspace workspace(
            List<String> include,
            Optional<List<WorkspaceMemberPath>> defaults) {
        return new AuthoredWorkspace(
                new LocalId("platform"),
                new AuthoredWorkspaceMembers(
                        include.stream().map(WorkspaceMemberPattern::new).toList(),
                        List.of(),
                        defaults),
                Optional.of(new AuthoredWorkspaceProjectDefaults(
                        Optional.of(new ProjectGroup("com.example")),
                        Optional.of(new ProjectVersion("1.0.0")),
                        Optional.of(new JavaFeatureRelease(21)),
                        Optional.of(new ProjectLicense.Identifier("Apache-2.0")))));
    }

    private static void assertInherited(EffectiveValue<?> value, String... fieldPath) {
        assertEquals(ValueOrigin.INHERITED, value.origin());
        assertEquals(
                new ManifestSource("zolt.toml", List.of(fieldPath)),
                value.source().orElseThrow());
    }

    private static void assertMessage(
            org.junit.jupiter.api.function.Executable action,
            String expected) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action);
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
