package sh.zolt.workspace.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;

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
        FinalWorkspaceFixtures.writeLegacyWorkspace(legacyRoot);
        FinalWorkspaceFixtures.writeFinalWorkspace(finalRoot);

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
        assertEquals(FinalWorkspaceFixtures.edges(legacy), FinalWorkspaceFixtures.edges(adapted), "workspace project edges");
        assertEquals(FinalWorkspaceFixtures.directories(legacy, legacyRoot), FinalWorkspaceFixtures.directories(adapted, finalRoot), "member directories");
        assertEquals(FinalWorkspaceFixtures.configs(legacy), FinalWorkspaceFixtures.configs(adapted), "member project configs");
    }

    @Test
    void everyLegacyWorkspaceScopeIsProjected() throws IOException {
        FinalWorkspaceFixtures.writeFinalWorkspace(finalRoot);

        Workspace adapted = loader.load(finalRoot);

        assertEquals(
                List.of(
                        "apps/api|modules/contract|compile|com.acme:contract|true|false",
                        "apps/api|modules/core|compile|com.acme:core|false|true",
                        "apps/api|modules/processor|processor|com.acme:processor|false|false",
                        "apps/api|modules/testkit|test|com.acme:testkit|false|false",
                        "modules/core|modules/processor|test-processor|com.acme:processor|false|false"),
                FinalWorkspaceFixtures.edges(adapted));
    }

    @Test
    void finalCaptureRetainsEveryManifestAsAFreshnessInput() throws IOException {
        FinalWorkspaceFixtures.writeFinalWorkspace(finalRoot);

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
    void workspaceMembersInheritTheHoistedProjectIdentity() throws IOException {
        FinalWorkspaceFixtures.writeFinalWorkspace(finalRoot);

        Workspace adapted = loader.load(finalRoot);
        ProjectConfig core = FinalWorkspaceFixtures.member(adapted, "modules/core");

        assertEquals("com.acme", core.project().group());
        assertEquals("1.4.0", core.project().version());
        assertEquals("21", core.project().java());
        assertEquals("https://repo.example.com/maven", core.repositories().get("company"));
        assertEquals("2026.1.0", core.platforms().get("com.acme:enterprise-platform"));
    }
}
