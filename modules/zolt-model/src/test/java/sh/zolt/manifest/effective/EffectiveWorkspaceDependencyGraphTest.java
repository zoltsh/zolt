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
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPackage;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.manifest.authored.AuthoredWorkspaceProjectDefaults;
import sh.zolt.project.toolchain.JavaFeatureRelease;

final class EffectiveWorkspaceDependencyGraphTest {
    private static final EffectiveManifestComposer COMPOSER = new EffectiveManifestComposer();
    private static final WorkspaceMemberPath API = path("modules/api");
    private static final WorkspaceMemberPath CORE = path("modules/core");

    @Test
    void resolvesInheritedIdentityAndRetainsAuthoredLaneAndOptionality() {
        AuthoredDependency declaration = dependency(
                DependencyLane.API,
                "com.example:core",
                new DependencySelector.Workspace(),
                true);
        EffectiveWorkspace workspace = COMPOSER.composeWorkspace(
                root(),
                Map.of(
                        API, member("api", declaration),
                        CORE, member("core")));

        EffectiveWorkspaceDependencyEdge edge = workspace.graph()
                .workspaceDependencies()
                .getFirst();

        assertEquals(API, edge.consumer());
        assertEquals(CORE, edge.provider());
        assertSame(declaration, edge.declaration());
        assertEquals(DependencyLane.API, edge.declaration().lane());
        assertTrue(edge.declaration().metadata().optional());
        assertThrows(
                UnsupportedOperationException.class,
                () -> workspace.graph().workspaceDependencies().add(edge));

        EffectiveWorkspace copy = new EffectiveWorkspace(workspace.root(), workspace.members());
        assertEquals(workspace, copy);
        assertEquals(workspace.hashCode(), copy.hashCode());
        assertEquals(workspace.toString(), copy.toString());
    }

    @Test
    void rejectsMissingAndSelfTargets() {
        assertMessage(
                () -> COMPOSER.composeWorkspace(
                        root(),
                        Map.of(API, member(
                                "api",
                                workspaceDependency("com.example:missing")))),
                "has no member with matching effective project identity");

        assertMessage(
                () -> COMPOSER.composeWorkspace(
                        root(),
                        Map.of(API, member(
                                "api",
                                workspaceDependency("com.example:api")))),
                "cannot depend on itself");
    }

    @Test
    void rejectsEveryNonLibraryPackageModeButAcceptsDefaultAndExplicitJar() {
        COMPOSER.composeWorkspace(
                root(),
                Map.of(
                        API, member("api", workspaceDependency("com.example:core")),
                        CORE, member("core")));
        COMPOSER.composeWorkspace(
                root(),
                Map.of(
                        API, member("api", workspaceDependency("com.example:core")),
                        CORE, packagedMember("core", AuthoredPackage.Mode.JAR)));

        for (AuthoredPackage.Mode mode : List.of(
                AuthoredPackage.Mode.UBER_JAR,
                AuthoredPackage.Mode.WAR,
                AuthoredPackage.Mode.SPRING_BOOT,
                AuthoredPackage.Mode.SPRING_BOOT_WAR,
                AuthoredPackage.Mode.QUARKUS)) {
            assertMessage(
                    () -> COMPOSER.composeWorkspace(
                            root(),
                            Map.of(
                                    API, member(
                                            "api",
                                            workspaceDependency("com.example:core")),
                                    CORE, packagedMember("core", mode))),
                    "package mode is `" + mode.configValue() + "`");
        }

        AuthoredBom importOnly = new AuthoredBom(
                Optional.empty(),
                Optional.empty(),
                Optional.of(Map.of(
                        new DependencyCoordinate("org.example:platform"),
                        new PlatformSelector.FixedVersion("1.0.0"))));
        AuthoredManifest bom = new WorkspaceManifestFixture()
                .identity(WorkspaceManifestFixture.sparseIdentity("core"))
                .packaging(new AuthoredPackaging(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(importOnly)))
                .create();
        assertMessage(
                () -> COMPOSER.composeWorkspace(
                        root(),
                        Map.of(
                                API, member(
                                        "api",
                                        workspaceDependency("com.example:core")),
                                CORE, bom)),
                "package mode is `bom`");
    }

    @Test
    void rejectsCyclesWithOneDeterministicPath() {
        WorkspaceMemberPath alpha = path("modules/alpha");
        WorkspaceMemberPath beta = path("modules/beta");
        WorkspaceMemberPath gamma = path("modules/gamma");

        assertMessage(
                () -> COMPOSER.composeWorkspace(
                        root(),
                        Map.of(
                                alpha, member(
                                        "alpha",
                                        workspaceDependency("com.example:beta")),
                                beta, member(
                                        "beta",
                                        workspaceDependency("com.example:gamma")),
                                gamma, member(
                                        "gamma",
                                        workspaceDependency("com.example:alpha")))),
                "modules/alpha -> modules/beta -> modules/gamma -> modules/alpha");
    }

    @Test
    void emitsManagedRequestsOnlyWhenAnEffectivePlatformImportExists() {
        AuthoredDependency managed = dependency(
                DependencyLane.RUNTIME,
                "org.example:driver",
                new DependencySelector.Managed(),
                false);
        assertMessage(
                () -> COMPOSER.composeWorkspace(
                        root(), Map.of(API, member("api", managed))),
                "no [platforms] entry is available");

        DependencyCoordinate platform = new DependencyCoordinate("org.example:platform");
        AuthoredManifest rootPlatform = new WorkspaceManifestFixture()
                .virtualRoot(workspace())
                .platforms(new AuthoredPlatforms(Map.of(
                        platform, new PlatformSelector.FixedVersion("1.0.0"))))
                .create();
        EffectiveWorkspace inherited = COMPOSER.composeWorkspace(
                rootPlatform, Map.of(API, member("api", managed)));
        EffectiveManagedDependencyRequest request = inherited.graph()
                .managedDependencies()
                .getFirst();
        assertEquals(API, request.owner());
        assertSame(managed, request.declaration());

        AuthoredManifest localPlatform = new WorkspaceManifestFixture()
                .identity(WorkspaceManifestFixture.sparseIdentity("api"))
                .dependencies(new AuthoredDependencies(List.of(managed)))
                .platforms(new AuthoredPlatforms(Map.of(
                        platform, new PlatformSelector.FixedVersion("1.0.0"))))
                .create();
        assertEquals(
                List.of(new EffectiveManagedDependencyRequest(API, managed)),
                COMPOSER.composeWorkspace(root(), Map.of(API, localPlatform))
                        .graph()
                        .managedDependencies());
    }

    @Test
    void publicWorkspaceConstructionCannotBypassGraphValidation() {
        EffectiveWorkspace valid = COMPOSER.composeWorkspace(
                root(), Map.of(API, member("api")));
        EffectiveManifest base = valid.members().get(API);
        AuthoredDependencies invalid = new AuthoredDependencies(List.of(
                workspaceDependency("com.example:missing")));
        var local = base.project().local();
        var replacedLocal = new sh.zolt.manifest.authored.ProjectLocalDomains(
                local.metadata(),
                Optional.of(invalid),
                local.dependencyConstraints(),
                local.dependencyPolicy(),
                local.build(),
                local.compiler(),
                local.resources(),
                local.tests(),
                local.generated(),
                local.packaging(),
                local.publishing());
        EffectiveManifest invalidMember = new EffectiveManifest(
                base.authored(),
                base.workspace(),
                new EffectiveProject(
                        base.project().identity(),
                        base.project().shared(),
                        replacedLocal));

        assertMessage(
                () -> new EffectiveWorkspace(valid.root(), Map.of(API, invalidMember)),
                "has no member with matching effective project identity");
    }

    private static AuthoredManifest root() {
        return new WorkspaceManifestFixture().virtualRoot(workspace()).create();
    }

    private static AuthoredWorkspace workspace() {
        return new AuthoredWorkspace(
                new LocalId("platform"),
                new AuthoredWorkspaceMembers(
                        List.of(new WorkspaceMemberPattern("modules/*")),
                        List.of(),
                        Optional.empty()),
                Optional.of(new AuthoredWorkspaceProjectDefaults(
                        Optional.of(new ProjectGroup("com.example")),
                        Optional.of(new ProjectVersion("1.0.0")),
                        Optional.of(new JavaFeatureRelease(21)),
                        Optional.empty())));
    }

    private static AuthoredManifest member(String name, AuthoredDependency... dependencies) {
        WorkspaceManifestFixture fixture = new WorkspaceManifestFixture()
                .identity(WorkspaceManifestFixture.sparseIdentity(name));
        if (dependencies.length > 0) {
            fixture.dependencies(new AuthoredDependencies(List.of(dependencies)));
        }
        return fixture.create();
    }

    private static AuthoredManifest packagedMember(String name, AuthoredPackage.Mode mode) {
        return new WorkspaceManifestFixture()
                .identity(WorkspaceManifestFixture.sparseIdentity(name))
                .packaging(packaging(mode))
                .create();
    }

    private static AuthoredPackaging packaging(AuthoredPackage.Mode mode) {
        return new AuthoredPackaging(
                Optional.of(new AuthoredPackage(
                        Optional.of(mode),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static AuthoredDependency workspaceDependency(String coordinate) {
        return dependency(
                DependencyLane.IMPLEMENTATION,
                coordinate,
                new DependencySelector.Workspace(),
                false);
    }

    private static AuthoredDependency dependency(
            DependencyLane lane,
            String coordinate,
            DependencySelector selector,
            boolean optional) {
        return new AuthoredDependency(
                lane,
                new DependencyCoordinate(coordinate),
                selector,
                new AuthoredDependencyMetadata(
                        optional,
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        List.of()));
    }

    private static WorkspaceMemberPath path(String value) {
        return new WorkspaceMemberPath(value);
    }

    private static void assertMessage(
            org.junit.jupiter.api.function.Executable action,
            String expected) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action);
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
