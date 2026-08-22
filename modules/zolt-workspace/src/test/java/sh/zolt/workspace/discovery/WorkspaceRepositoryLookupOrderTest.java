package sh.zolt.workspace.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.maven.repository.RepositoryAccess;
import sh.zolt.maven.repository.RepositoryAccessPlanner;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyResolver;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;

/**
 * Design §8.5 makes repository lookup order authored policy, and fetching is first-match-wins, so the
 * order a member ends up querying decides which repository serves an artifact available from more
 * than one. The root owns that order for the whole workspace (§8.7), and it has to survive every
 * projection between the root manifest and the plan a member resolves against.
 *
 * <p>Each case is asserted twice over the same repository set in opposite orders. One
 * {@code Map.copyOf} anywhere on the path publishes a single salt-randomized iteration order for that
 * key set, which cannot satisfy both directions — so this fails on the projection rather than on a
 * lucky hash.
 */
final class WorkspaceRepositoryLookupOrderTest {
    @TempDir
    private Path root;

    @Test
    void theRootLookupOrderReachesEveryWorkspaceRepositoryProjection() throws IOException {
        assertLookupOrder(List.of("zeta", "alpha", "central"));
        assertLookupOrder(List.of("alpha", "zeta", "central"));
    }

    private void assertLookupOrder(List<String> order) throws IOException {
        Workspace workspace = load(order);
        WorkspaceMember member = workspace.members().stream()
                .filter(candidate -> candidate.path().equals("modules/core"))
                .findFirst()
                .orElseThrow();
        ProjectConfig merged = new WorkspaceMemberPolicyResolver().merge(workspace, member);

        assertEquals(
                order,
                List.copyOf(workspace.config().repositorySettings().keySet()),
                "the loader's repository settings carry the effective lookup order");
        assertEquals(
                order,
                List.copyOf(workspace.config().repositories().keySet()),
                "the legacy URL projection is the map the member policy merge seeds from");
        assertEquals(
                order,
                List.copyOf(merged.repositorySettings().keySet()),
                "the merged member config keeps the root's order");
        assertEquals(
                order,
                new RepositoryAccessPlanner(name -> "value").plan(merged).stream()
                        .map(RepositoryAccess::id)
                        .toList(),
                "first-match-wins fetching queries the member's repositories in that order");
    }

    private Workspace load(List<String> order) throws IOException {
        Path workspaceRoot = Files.createDirectories(root.resolve(String.join("-", order)));
        write(workspaceRoot, "zolt.toml", """
                [workspace]
                name = "ordered"

                [workspace.members]
                include = ["modules/*"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21

                [repositories]
                order = [%s]

                [repositories.alpha]
                url = "https://repo.example/alpha"

                [repositories.zeta]
                url = "https://repo.example/zeta"
                """.formatted(order.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + ", " + b).orElseThrow()));
        write(workspaceRoot, "modules/core/zolt.toml", """
                [project]
                name = "core"
                """);
        return new ManifestWorkspaceLoader().load(workspaceRoot);
    }

    private static void write(Path directory, String relative, String contents) throws IOException {
        Path path = directory.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }
}
