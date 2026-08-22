package sh.zolt.workspace.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.project.BomSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.service.Workspace;

/**
 * Workspace shapes only the final language expresses: implicit-all selection, hoisted BOM members,
 * root-owned shared configuration, and the root-project workspace.
 */
final class ManifestWorkspaceLoaderShapeTest {
    private final ManifestWorkspaceLoader loader = new ManifestWorkspaceLoader();

    @TempDir
    private Path finalRoot;

    @Test
    void implicitAllSelectionReportsNoLegacyDefaultMembers() throws IOException {
        FinalWorkspaceFixtures.write(finalRoot, "zolt.toml", """
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["modules/*"]

                [workspace.project]
                group = "com.acme"
                version = "1.4.0"
                java = 21
                """);
        FinalWorkspaceFixtures.write(finalRoot, "modules/core/zolt.toml", """
                [project]
                name = "core"
                """);

        Workspace adapted = loader.load(finalRoot);

        assertEquals(List.of(), adapted.config().defaultMembers());
        assertEquals(List.of("modules/core"), adapted.config().members());
    }

    @Test
    void workspaceBomMemberSelectsConsumableMembers() throws IOException {
        FinalWorkspaceFixtures.write(finalRoot, "zolt.toml", """
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/*", "modules/*"]

                [workspace.project]
                group = "com.acme"
                version = "1.4.0"
                java = 21

                [versions]
                jackson = "2.19.0"
                """);
        FinalWorkspaceFixtures.write(finalRoot, "apps/api/zolt.toml", """
                [project]
                name = "api"
                main = "com.acme.api.Main"

                [package]
                mode = "uber-jar"
                """);
        FinalWorkspaceFixtures.write(finalRoot, "modules/core/zolt.toml", """
                [project]
                name = "core"
                """);
        FinalWorkspaceFixtures.write(finalRoot, "modules/platform-bom/zolt.toml", """
                [project]
                name = "platform-bom"

                [bom]
                members = true
                exclude = ["apps/api"]

                [bom.versions]
                "org.postgresql:postgresql" = "42.7.4"

                [bom.imports]
                "com.fasterxml.jackson:jackson-bom" = { versionRef = "jackson" }
                """);

        Workspace adapted = loader.load(finalRoot);
        ProjectConfig bom = FinalWorkspaceFixtures.member(adapted, "modules/platform-bom");

        assertEquals(PackageMode.BOM, bom.packageSettings().mode());
        assertEquals("", bom.project().java(), "design §12.6 gives a BOM no Java release");
        BomSettings settings = bom.packageSettings().bom();
        assertTrue(settings.members().all());
        assertEquals(List.of("apps/api"), settings.members().exclude());
        assertEquals(
                List.of(new BomSettings.ManagedVersion(
                        "org.postgresql:postgresql", "42.7.4", null, null, null)),
                settings.versions());
        assertEquals(
                List.of(new BomSettings.ImportedBom(
                        "com.fasterxml.jackson:jackson-bom", "2.19.0", "jackson")),
                settings.imports());
    }

    @Test
    void memberLocalCredentialsAndPlatformsStayOutOfTheWorkspaceView() throws IOException {
        FinalWorkspaceFixtures.write(finalRoot, "zolt.toml", """
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["modules/*"]

                [workspace.project]
                group = "com.acme"
                version = "1.4.0"
                java = 21

                [credentials.company]
                usernameEnv = "MAVEN_USERNAME"
                passwordEnv = "MAVEN_PASSWORD"

                [platforms]
                "com.acme:enterprise-platform" = "2026.1.0"
                """);
        FinalWorkspaceFixtures.write(finalRoot, "modules/core/zolt.toml", """
                [project]
                name = "core"

                [credentials.member-only]
                tokenEnv = "MEMBER_TOKEN"

                [platforms]
                "com.acme:member-platform" = "1.0.0"
                """);

        Workspace adapted = loader.load(finalRoot);

        assertEquals(
                List.of("company"),
                List.copyOf(adapted.config().repositoryCredentials().keySet()),
                "design §8.7 keeps member-local credentials out of the root-owned universe");
        assertEquals(
                Map.of("com.acme:enterprise-platform", "2026.1.0"),
                adapted.config().platforms(),
                "member platforms belong to the member, not to the workspace view");
        ProjectConfig core = FinalWorkspaceFixtures.member(adapted, "modules/core");
        assertEquals(
                "MEMBER_TOKEN",
                core.repositoryCredentials().get("member-only").tokenEnv().orElseThrow());
        assertEquals("1.0.0", core.platforms().get("com.acme:member-platform"));
        assertEquals("2026.1.0", core.platforms().get("com.acme:enterprise-platform"));
    }

    @Test
    void rootProjectWorkspaceIncludesTheRootAsAMember() throws IOException {
        FinalWorkspaceFixtures.write(finalRoot, "zolt.toml", """
                [workspace]
                name = "platform"

                [workspace.members]
                default = ["."]
                include = [".", "modules/*"]

                [workspace.project]
                group = "com.example"
                version = "1.4.0"
                java = 21

                [platforms]
                "com.example:platform" = "2026.1.0"

                [project]
                name = "platform-root"
                """);
        FinalWorkspaceFixtures.write(finalRoot, "modules/core/zolt.toml", """
                [project]
                name = "core"
                """);

        Workspace adapted = loader.load(finalRoot);

        assertEquals(List.of(".", "modules/core"), adapted.config().members());
        assertEquals(List.of("."), adapted.config().defaultMembers());
        assertEquals(
                Map.of("com.example:platform", "2026.1.0"),
                adapted.config().platforms());
        assertEquals("platform-root", FinalWorkspaceFixtures.member(adapted, ".").project().name());
        assertEquals(
                finalRoot.toAbsolutePath().normalize(),
                adapted.members().getFirst().directory());
    }

    /**
     * The root {@code .} member is an ordinary member in the build graph: a workspace dependency it
     * declares orders its provider before it, exactly as for any other member (design §4.4).
     */
    @Test
    void rootMemberIsOrderedAfterTheProviderItDependsOn() throws IOException {
        FinalWorkspaceFixtures.write(finalRoot, "zolt.toml", """
                [workspace]
                name = "platform"

                [workspace.members]
                default = ["."]
                include = [".", "modules/*"]

                [workspace.project]
                group = "com.example"
                version = "1.4.0"
                java = 21

                [project]
                name = "platform-root"

                [dependencies]
                "com.example:core" = { workspace = true }
                """);
        FinalWorkspaceFixtures.write(finalRoot, "modules/core/zolt.toml", """
                [project]
                name = "core"
                """);

        Workspace adapted = loader.load(finalRoot);

        assertEquals(List.of("modules/core", "."), adapted.buildOrder());
    }

    @Test
    void workspaceSelectorsInRuntimeLanesAreRejected() throws IOException {
        FinalWorkspaceFixtures.write(finalRoot, "zolt.toml", """
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/*", "modules/*"]

                [workspace.project]
                group = "com.acme"
                version = "1.4.0"
                java = 21
                """);
        FinalWorkspaceFixtures.write(finalRoot, "apps/api/zolt.toml", """
                [project]
                name = "api"

                [dependencies.runtime]
                "com.acme:core" = { workspace = true }
                """);
        FinalWorkspaceFixtures.write(finalRoot, "modules/core/zolt.toml", """
                [project]
                name = "core"
                """);

        WorkspaceConfigException failure =
                assertThrows(WorkspaceConfigException.class, () -> loader.load(finalRoot));
        assertTrue(
                failure.getMessage().contains("RUNTIME"),
                () -> "expected a lane diagnostic, got: " + failure.getMessage());
    }
}
