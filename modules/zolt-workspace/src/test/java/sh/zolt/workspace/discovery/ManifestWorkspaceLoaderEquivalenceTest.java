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
import sh.zolt.project.BomSettings;
import sh.zolt.project.PackageMode;
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
    /** Root-owned configuration every legacy member had to repeat before the final language. */
    private static final String LEGACY_SHARED = """
            [repositories]
            central = "https://repo.maven.apache.org/maven2"
            company = { url = "https://repo.example.com/maven", credentials = "company" }

            [repositoryCredentials.company]
            usernameEnv = "MAVEN_USERNAME"
            passwordEnv = "MAVEN_PASSWORD"

            [platforms]
            "com.acme:enterprise-platform" = "2026.1.0"
            """;

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
    void everyLegacyWorkspaceScopeIsProjected() throws IOException {
        writeFinalWorkspace();

        Workspace adapted = loader.load(finalRoot);

        assertEquals(
                List.of(
                        "apps/api|modules/contract|compile|com.acme:contract|true|false",
                        "apps/api|modules/core|compile|com.acme:core|false|true",
                        "apps/api|modules/processor|processor|com.acme:processor|false|false",
                        "apps/api|modules/testkit|test|com.acme:testkit|false|false",
                        "modules/core|modules/processor|test-processor|com.acme:processor|false|false"),
                edges(adapted));
    }

    @Test
    void finalCaptureRetainsEveryManifestAsAFreshnessInput() throws IOException {
        writeFinalWorkspace();

        Workspace adapted = loader.load(finalRoot);

        Map<String, String> digests = adapted.inputs().digestsRelativeTo(adapted.root());
        assertTrue(digests.containsKey("zolt.toml"), () -> "root manifest missing from " + digests.keySet());
        for (WorkspaceMember member : adapted.members()) {
            String relative = member.path() + "/zolt.toml";
            assertTrue(
                    digests.containsKey(relative),
                    () -> "member manifest " + relative + " missing from " + digests.keySet());
        }
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
        ProjectConfig core = member(adapted, "modules/core");

        assertEquals("com.acme", core.project().group());
        assertEquals("1.4.0", core.project().version());
        assertEquals("21", core.project().java());
        assertEquals("https://repo.example.com/maven", core.repositories().get("company"));
        assertEquals("2026.1.0", core.platforms().get("com.acme:enterprise-platform"));
    }

    @Test
    void workspaceBomMemberSelectsConsumableMembers() throws IOException {
        write(finalRoot, "zolt.toml", """
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
        write(finalRoot, "apps/api/zolt.toml", """
                [project]
                name = "api"
                main = "com.acme.api.Main"

                [package]
                mode = "uber-jar"
                """);
        write(finalRoot, "modules/core/zolt.toml", """
                [project]
                name = "core"
                """);
        write(finalRoot, "modules/platform-bom/zolt.toml", """
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
        ProjectConfig bom = member(adapted, "modules/platform-bom");

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
        write(finalRoot, "zolt.toml", """
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
        write(finalRoot, "modules/core/zolt.toml", """
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
        ProjectConfig core = member(adapted, "modules/core");
        assertEquals(
                "MEMBER_TOKEN",
                core.repositoryCredentials().get("member-only").tokenEnv().orElseThrow());
        assertEquals("1.0.0", core.platforms().get("com.acme:member-platform"));
        assertEquals("2026.1.0", core.platforms().get("com.acme:enterprise-platform"));
    }

    @Test
    void rootProjectWorkspaceIncludesTheRootAsAMember() throws IOException {
        write(finalRoot, "zolt.toml", """
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
        write(finalRoot, "modules/core/zolt.toml", """
                [project]
                name = "core"
                """);

        Workspace adapted = loader.load(finalRoot);

        assertEquals(List.of(".", "modules/core"), adapted.config().members());
        assertEquals(List.of("."), adapted.config().defaultMembers());
        assertEquals(
                Map.of("com.example:platform", "2026.1.0"),
                adapted.config().platforms());
        assertEquals("platform-root", member(adapted, ".").project().name());
        assertEquals(
                finalRoot.toAbsolutePath().normalize(),
                adapted.members().getFirst().directory());
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
                members = ["apps/api", "modules/contract", "modules/core", "modules/processor", "modules/testkit"]
                defaultMembers = ["apps/api"]

                """ + LEGACY_SHARED);
        write(legacyRoot, "apps/api/zolt.toml", legacyMember("api", """
                main = "com.acme.api.Main"
                """, """
                [api.dependencies]
                "com.acme:contract" = { workspace = "modules/contract" }

                [dependencies]
                "com.acme:core" = { workspace = "modules/core", optional = true }

                [annotationProcessors]
                "com.acme:processor" = { workspace = "modules/processor" }

                [test.dependencies]
                "com.acme:testkit" = { workspace = "modules/testkit" }
                "org.junit.jupiter:junit-jupiter" = "5.13.4"
                """));
        write(legacyRoot, "modules/contract/zolt.toml", legacyMember("contract", "", ""));
        write(legacyRoot, "modules/core/zolt.toml", legacyMember("core", "", """
                [api.dependencies]
                "org.slf4j:slf4j-api" = "2.0.17"

                [test.annotationProcessors]
                "com.acme:processor" = { workspace = "modules/processor" }
                """));
        write(legacyRoot, "modules/processor/zolt.toml", legacyMember("processor", "", ""));
        write(legacyRoot, "modules/testkit/zolt.toml", legacyMember("testkit", "", ""));
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
                "com.acme:core" = { workspace = true, optional = true }

                [dependencies.api]
                "com.acme:contract" = { workspace = true }

                [dependencies.test]
                "com.acme:testkit" = { workspace = true }
                "org.junit.jupiter:junit-jupiter" = "5.13.4"

                [dependencies.processor]
                "com.acme:processor" = { workspace = true }
                """);
        write(finalRoot, "modules/contract/zolt.toml", """
                [project]
                name = "contract"
                """);
        write(finalRoot, "modules/core/zolt.toml", """
                [project]
                name = "core"

                [dependencies.api]
                "org.slf4j:slf4j-api" = "2.0.17"

                [dependencies.test-processor]
                "com.acme:processor" = { workspace = true }
                """);
        write(finalRoot, "modules/processor/zolt.toml", """
                [project]
                name = "processor"
                """);
        write(finalRoot, "modules/testkit/zolt.toml", """
                [project]
                name = "testkit"
                """);
    }

    private static String legacyMember(String name, String identityExtras, String sections) {
        return """
                [project]
                name = "%s"
                version = "1.4.0"
                group = "com.acme"
                java = "21"
                %s
                """.formatted(name, identityExtras)
                + LEGACY_SHARED
                + (sections.isEmpty() ? "" : "\n" + sections);
    }

    private static void write(Path root, String relativePath, String content) throws IOException {
        Path path = root.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static ProjectConfig member(Workspace workspace, String path) {
        return workspace.members().stream()
                .filter(member -> member.path().equals(path))
                .findFirst()
                .orElseThrow()
                .config();
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
