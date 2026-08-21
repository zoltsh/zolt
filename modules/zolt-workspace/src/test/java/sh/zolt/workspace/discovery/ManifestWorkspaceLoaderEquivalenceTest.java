package sh.zolt.workspace.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;

/**
 * A root-plus-members workspace written twice — once in the legacy dialect, once in the final
 * language — asserted to produce the same legacy {@link Workspace} graph.
 *
 * <p>The legacy half goes through {@link LegacyWorkspaceDialect}, the one helper the cleanup phase
 * deletes with {@link WorkspaceDiscoveryService}.
 */
final class ManifestWorkspaceLoaderEquivalenceTest {
    private final ManifestWorkspaceLoader loader = new ManifestWorkspaceLoader();

    @TempDir
    private Path legacyRoot;

    @TempDir
    private Path finalRoot;

    @Test
    void workspacePairIsEquivalent() throws IOException {
        writeLegacyWorkspace();
        writeFinalWorkspace();

        Workspace legacy = LegacyWorkspaceDialect.load(legacyRoot);
        Workspace adapted = loader.load(finalRoot);

        assertEquals(legacy.config().name(), adapted.config().name(), "workspace name");
        assertEquals(legacy.config().members(), adapted.config().members(), "workspace members");
        assertEquals(
                legacy.config().defaultMembers(),
                adapted.config().defaultMembers(),
                "workspace default members");
        assertEquals(legacy.config().repositories(), adapted.config().repositories(), "repositories");
        assertEquals(
                legacy.config().repositorySettings(),
                adapted.config().repositorySettings(),
                "repository settings");
        assertEquals(
                legacy.config().repositoryCredentials(),
                adapted.config().repositoryCredentials(),
                "repository credentials");
        assertEquals(legacy.config().platforms(), adapted.config().platforms(), "workspace platforms");
        assertEquals(legacy.buildOrder(), adapted.buildOrder(), "build order");
        assertEquals(edges(legacy), edges(adapted), "workspace project edges");
        assertEquals(directories(legacy, legacyRoot), directories(adapted, finalRoot), "member directories");
        assertEquals(configs(legacy), configs(adapted), "member project configs");
    }

    @Test
    void finalCaptureRetainsEveryManifestAsAFreshnessInput() throws IOException {
        writeFinalWorkspace();

        Workspace adapted = loader.load(finalRoot);

        Map<String, String> digests = adapted.inputs().digestsRelativeTo(adapted.root());
        assertTrue(digests.containsKey("zolt.toml"), () -> "root manifest missing from " + digests.keySet());
        assertTrue(
                digests.containsKey("apps/api/zolt.toml"),
                () -> "member manifest missing from " + digests.keySet());
        assertTrue(
                digests.containsKey("modules/core/zolt.toml"),
                () -> "member manifest missing from " + digests.keySet());
    }

    @Test
    void implicitAllSelectionReportsNoLegacyDefaultMembers() throws IOException {
        write(finalRoot, "zolt.toml", """
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["modules/*"]

                [workspace.project]
                group = "com.acme"
                version = "1.4.0"
                java = 21
                """);
        write(finalRoot, "modules/core/zolt.toml", """
                [project]
                name = "core"
                """);

        Workspace adapted = loader.load(finalRoot);

        assertEquals(List.of(), adapted.config().defaultMembers());
        assertEquals(List.of("modules/core"), adapted.config().members());
    }

    @Test
    void workspaceMembersInheritTheHoistedProjectIdentity() throws IOException {
        writeFinalWorkspace();

        Workspace adapted = loader.load(finalRoot);
        ProjectConfig core = adapted.members().stream()
                .filter(member -> member.path().equals("modules/core"))
                .findFirst()
                .orElseThrow()
                .config();

        assertEquals("com.acme", core.project().group());
        assertEquals("1.4.0", core.project().version());
        assertEquals("21", core.project().java());
        assertEquals("https://repo.example.com/maven", core.repositories().get("company"));
    }

    @Test
    void workspaceSelectorsInRuntimeLanesAreRejected() throws IOException {
        write(finalRoot, "zolt.toml", """
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/*", "modules/*"]

                [workspace.project]
                group = "com.acme"
                version = "1.4.0"
                java = 21
                """);
        write(finalRoot, "apps/api/zolt.toml", """
                [project]
                name = "api"

                [dependencies.runtime]
                "com.acme:core" = { workspace = true }
                """);
        write(finalRoot, "modules/core/zolt.toml", """
                [project]
                name = "core"
                """);

        WorkspaceConfigException failure =
                assertThrows(WorkspaceConfigException.class, () -> loader.load(finalRoot));
        assertTrue(
                failure.getMessage().contains("RUNTIME"),
                () -> "expected a lane diagnostic, got: " + failure.getMessage());
    }

    private void writeLegacyWorkspace() throws IOException {
        write(legacyRoot, "zolt-workspace.toml", """
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core"]
                defaultMembers = ["apps/api"]

                [repositories]
                central = "https://repo.maven.apache.org/maven2"
                company = { url = "https://repo.example.com/maven", credentials = "company" }

                [repositoryCredentials.company]
                usernameEnv = "MAVEN_USERNAME"
                passwordEnv = "MAVEN_PASSWORD"

                [platforms]
                "com.acme:enterprise-platform" = "2026.1.0"
                """);
        write(legacyRoot, "apps/api/zolt.toml", """
                [project]
                name = "api"
                version = "1.4.0"
                group = "com.acme"
                java = "21"
                main = "com.acme.api.Main"

                [repositories]
                central = "https://repo.maven.apache.org/maven2"
                company = { url = "https://repo.example.com/maven", credentials = "company" }

                [repositoryCredentials.company]
                usernameEnv = "MAVEN_USERNAME"
                passwordEnv = "MAVEN_PASSWORD"

                [platforms]
                "com.acme:enterprise-platform" = "2026.1.0"

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }

                [test.dependencies]
                "org.junit.jupiter:junit-jupiter" = "5.13.4"
                """);
        write(legacyRoot, "modules/core/zolt.toml", """
                [project]
                name = "core"
                version = "1.4.0"
                group = "com.acme"
                java = "21"

                [repositories]
                central = "https://repo.maven.apache.org/maven2"
                company = { url = "https://repo.example.com/maven", credentials = "company" }

                [repositoryCredentials.company]
                usernameEnv = "MAVEN_USERNAME"
                passwordEnv = "MAVEN_PASSWORD"

                [platforms]
                "com.acme:enterprise-platform" = "2026.1.0"

                [api.dependencies]
                "org.slf4j:slf4j-api" = "2.0.17"
                """);
    }

    private void writeFinalWorkspace() throws IOException {
        write(finalRoot, "zolt.toml", """
                [workspace]
                name = "acme-platform"

                [workspace.members]
                default = ["apps/api"]
                include = ["apps/*", "modules/*"]

                [workspace.project]
                group = "com.acme"
                version = "1.4.0"
                java = 21

                [repositories.company]
                url = "https://repo.example.com/maven"
                credentials = "company"

                [credentials.company]
                usernameEnv = "MAVEN_USERNAME"
                passwordEnv = "MAVEN_PASSWORD"

                [platforms]
                "com.acme:enterprise-platform" = "2026.1.0"
                """);
        write(finalRoot, "apps/api/zolt.toml", """
                [project]
                name = "api"
                main = "com.acme.api.Main"

                [dependencies]
                "com.acme:core" = { workspace = true }

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.13.4"
                """);
        write(finalRoot, "modules/core/zolt.toml", """
                [project]
                name = "core"

                [dependencies.api]
                "org.slf4j:slf4j-api" = "2.0.17"
                """);
    }

    private static void write(Path root, String relativePath, String content) throws IOException {
        Path path = root.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static List<String> edges(Workspace workspace) {
        return workspace.edges().stream()
                .map(ManifestWorkspaceLoaderEquivalenceTest::describe)
                .sorted()
                .toList();
    }

    private static String describe(WorkspaceProjectEdge edge) {
        return String.join(
                "|",
                edge.from(),
                edge.to(),
                edge.scope(),
                edge.coordinate(),
                Boolean.toString(edge.exported()),
                Boolean.toString(edge.optional()));
    }

    private static List<String> directories(Workspace workspace, Path root) {
        return workspace.members().stream()
                .map(member -> root.toAbsolutePath().normalize()
                        .relativize(member.directory())
                        .toString()
                        .replace('\\', '/'))
                .toList();
    }

    private static Map<String, ProjectConfig> configs(Workspace workspace) {
        Map<String, ProjectConfig> configs = new TreeMap<>();
        for (WorkspaceMember member : workspace.members()) {
            configs.put(member.path(), member.config());
        }
        return configs;
    }
}
