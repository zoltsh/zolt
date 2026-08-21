package sh.zolt.manifest.effective;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPackage;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.manifest.authored.AuthoredWorkspaceProjectDefaults;
import sh.zolt.project.toolchain.JavaFeatureRelease;

final class EffectiveWorkspaceBomGraphTest {
    private static final EffectiveManifestComposer COMPOSER = new EffectiveManifestComposer();
    private static final WorkspaceMemberPath APP = path("apps/service");
    private static final WorkspaceMemberPath BOM = path("modules/platform-bom");
    private static final WorkspaceMemberPath CORE = path("modules/core");
    private static final WorkspaceMemberPath HTTP = path("modules/http");

    @Test
    void allSelectionFiltersSelfAndNonLibrariesAndHonorsExactExclusions() {
        EffectiveWorkspace workspace = COMPOSER.composeWorkspace(
                root(),
                Map.of(
                        APP, packagedMember("service", AuthoredPackage.Mode.SPRING_BOOT),
                        BOM, bomMember("platform-bom", all(List.of(HTTP))),
                        CORE, member("core"),
                        HTTP, member("http")));

        assertEquals(
                Map.of(BOM, List.of(CORE)),
                workspace.graph().resolvedBomMembers());
        assertThrows(
                UnsupportedOperationException.class,
                () -> workspace.graph().resolvedBomMembers().put(BOM, List.of()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> workspace.graph().resolvedBomMembers().get(BOM).add(HTTP));
    }

    @Test
    void allSelectionMayResolveEmptyAndAllowsRedundantFinalMemberExclusions() {
        EffectiveWorkspace workspace = COMPOSER.composeWorkspace(
                root(),
                Map.of(
                        APP, packagedMember("service", AuthoredPackage.Mode.SPRING_BOOT),
                        BOM, bomMember("platform-bom", all(List.of(APP, BOM)))));

        assertTrue(workspace.graph().resolvedBomMembers().containsKey(BOM));
        assertEquals(List.of(), workspace.graph().resolvedBomMembers().get(BOM));
    }

    @Test
    void explicitSelectionIsCanonicalAndRequiresFinalConsumableNonSelfMembers() {
        EffectiveWorkspace workspace = COMPOSER.composeWorkspace(
                root(),
                Map.of(
                        BOM, bomMember(
                                "platform-bom",
                                explicit(List.of(HTTP, CORE))),
                        CORE, member("core"),
                        HTTP, member("http")));
        assertEquals(
                List.of(CORE, HTTP),
                workspace.graph().resolvedBomMembers().get(BOM));

        assertMessage(
                () -> COMPOSER.composeWorkspace(
                        root(),
                        Map.of(
                                BOM, bomMember(
                                        "platform-bom",
                                        explicit(List.of(path("modules/missing")))),
                                CORE, member("core"))),
                "is not in the final workspace member set");
        assertMessage(
                () -> COMPOSER.composeWorkspace(
                        root(),
                        Map.of(
                                BOM, bomMember(
                                        "platform-bom",
                                        explicit(List.of(BOM))),
                                CORE, member("core"))),
                "cannot include itself");
        assertMessage(
                () -> COMPOSER.composeWorkspace(
                        root(),
                        Map.of(
                                APP, packagedMember(
                                        "service", AuthoredPackage.Mode.SPRING_BOOT),
                                BOM, bomMember(
                                        "platform-bom",
                                        explicit(List.of(APP))))),
                "not a consumable library JAR");
    }

    @Test
    void allSelectionRejectsAnExclusionOutsideTheFinalMemberSet() {
        assertMessage(
                () -> COMPOSER.composeWorkspace(
                        root(),
                        Map.of(
                                BOM, bomMember(
                                        "platform-bom",
                                        all(List.of(path("modules/missing")))),
                                CORE, member("core"))),
                "BOM exclusion `modules/missing` is not in the final workspace member set");
    }

    @Test
    void importOnlyBomHasNoResolvedMemberSelection() {
        AuthoredBom importOnly = new AuthoredBom(
                Optional.empty(),
                Optional.empty(),
                Optional.of(Map.of(
                        new DependencyCoordinate("org.example:upstream-bom"),
                        new PlatformSelector.FixedVersion("1.0.0"))));
        EffectiveWorkspace workspace = COMPOSER.composeWorkspace(
                root(),
                Map.of(
                        BOM, bomMember("platform-bom", importOnly),
                        CORE, member("core")));

        assertFalse(workspace.graph().resolvedBomMembers().containsKey(BOM));
    }

    private static AuthoredManifest root() {
        return new WorkspaceManifestFixture()
                .virtualRoot(new AuthoredWorkspace(
                        new LocalId("platform"),
                        new AuthoredWorkspaceMembers(
                                List.of(
                                        new WorkspaceMemberPattern("apps/*"),
                                        new WorkspaceMemberPattern("modules/*")),
                                List.of(),
                                Optional.empty()),
                        Optional.of(new AuthoredWorkspaceProjectDefaults(
                                Optional.of(new ProjectGroup("com.example")),
                                Optional.of(new ProjectVersion("1.0.0")),
                                Optional.of(new JavaFeatureRelease(21)),
                                Optional.empty()))))
                .create();
    }

    private static AuthoredManifest member(String name) {
        return new WorkspaceManifestFixture()
                .identity(WorkspaceManifestFixture.sparseIdentity(name))
                .create();
    }

    private static AuthoredManifest packagedMember(
            String name,
            AuthoredPackage.Mode mode) {
        return new WorkspaceManifestFixture()
                .identity(WorkspaceManifestFixture.sparseIdentity(name))
                .packaging(new AuthoredPackaging(
                        Optional.of(new AuthoredPackage(
                                Optional.of(mode),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty())),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()))
                .create();
    }

    private static AuthoredManifest bomMember(String name, AuthoredBom bom) {
        return new WorkspaceManifestFixture()
                .identity(WorkspaceManifestFixture.sparseIdentity(name))
                .packaging(new AuthoredPackaging(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(bom)))
                .create();
    }

    private static AuthoredBom all(List<WorkspaceMemberPath> exclusions) {
        return new AuthoredBom(
                Optional.of(new AuthoredBom.Members(
                        new AuthoredBom.AllMembers(), exclusions)),
                Optional.empty(),
                Optional.empty());
    }

    private static AuthoredBom explicit(List<WorkspaceMemberPath> selected) {
        return new AuthoredBom(
                Optional.of(new AuthoredBom.Members(
                        new AuthoredBom.ExplicitMembers(selected), List.of())),
                Optional.empty(),
                Optional.empty());
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
