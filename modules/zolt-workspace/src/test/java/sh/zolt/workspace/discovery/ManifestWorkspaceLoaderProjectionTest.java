package sh.zolt.workspace.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;

/**
 * A root-plus-members workspace asserted to reach the expected {@link Workspace} graph through the
 * final loader: membership, shared root configuration, edges, capture, and hoisted identity.
 */
final class ManifestWorkspaceLoaderProjectionTest {
    private final ManifestWorkspaceLoader loader = new ManifestWorkspaceLoader();

    @TempDir
    private Path finalRoot;

    @Test
    void rootConfigurationAndMembershipReachTheWorkspace() throws IOException {
        FinalWorkspaceFixtures.writeFinalWorkspace(finalRoot);

        Workspace adapted = loader.load(finalRoot);

        assertEquals("acme-platform", adapted.config().name());
        assertEquals(
                List.of("apps/api", "modules/contract", "modules/core", "modules/processor",
                        "modules/testkit"),
                adapted.config().members());
        assertEquals(List.of("apps/api"), adapted.config().defaultMembers());
        assertEquals(
                "https://repo.maven.apache.org/maven2",
                adapted.config().repositories().get("central"),
                "design §6.1 keeps Maven Central implicit");
        assertEquals(
                "https://repo.example.com/maven", adapted.config().repositories().get("company"));
        assertEquals(
                Optional.of("MAVEN_USERNAME"),
                adapted.config().repositoryCredentials().get("company").usernameEnv());
        assertEquals(
                Optional.of("MAVEN_PASSWORD"),
                adapted.config().repositoryCredentials().get("company").passwordEnv());
        assertEquals(
                Map.of("com.acme:enterprise-platform", "2026.1.0"), adapted.config().platforms());
        assertEquals(
                List.of("modules/contract", "modules/processor", "modules/core", "modules/testkit",
                        "apps/api"),
                adapted.buildOrder());
        assertEquals(
                List.of("apps/api", "modules/contract", "modules/core", "modules/processor",
                        "modules/testkit"),
                FinalWorkspaceFixtures.directories(adapted, finalRoot));
    }

    @Test
    void everyWorkspaceScopeIsProjected() throws IOException {
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
