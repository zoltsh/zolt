package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.resolve.ResolveException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WorkspaceExternalIdentityTest {
    private static final PackageId ROOT = new PackageId("com.example", "root");
    private final WorkspaceExternalPackageSelector selector = new WorkspaceExternalPackageSelector();

    @Test
    void refusesDifferentRepositoryBytesForTheSameSelectedIdentity() {
        List<LockPackage> candidates = List.of(
                candidate("apps/api", "jar-a", "pom-shared", List.of(), List.of()),
                candidate("apps/worker", "jar-b", "pom-shared", List.of(), List.of()));

        ResolveException exception =
                assertThrows(ResolveException.class, () -> selector.selectMaterialized(candidates));

        assertTrue(exception.getMessage().contains("resolved different bytes"));
        assertTrue(exception.getMessage().contains("apps/api"));
        assertTrue(exception.getMessage().contains("apps/worker"));
        assertTrue(exception.getMessage().contains("zolt resolve --workspace"));
    }

    @Test
    void refusesDifferentBytesAcrossSelectedScopeCopies() {
        List<LockPackage> candidates = List.of(
                candidate(
                        "apps/api",
                        DependencyScope.COMPILE,
                        "jar-a",
                        "pom-shared",
                        List.of(),
                        List.of()),
                candidate(
                        "apps/worker",
                        DependencyScope.RUNTIME,
                        "jar-b",
                        "pom-shared",
                        List.of(),
                        List.of()));

        ResolveException exception =
                assertThrows(ResolveException.class, () -> selector.selectMaterialized(candidates));

        assertTrue(exception.getMessage().contains("scope=compile"));
        assertTrue(exception.getMessage().contains("scope=runtime"));
    }

    @Test
    void preservesMemberGraphFactsIndependentOfCandidateOrder() {
        LockPackage api = candidate(
                "apps/api",
                "jar-shared",
                "pom-shared",
                List.of(),
                List.of("edge-exclusion: com.example:leaf"));
        LockPackage worker = candidate(
                "apps/worker",
                "jar-shared",
                "pom-shared",
                List.of("com.example:leaf:1.0.0:jar:compile"),
                List.of());

        WorkspaceExternalSelection forward = selector.selectMaterialized(List.of(api, worker));
        WorkspaceExternalSelection reverse = selector.selectMaterialized(List.of(worker, api));

        assertEquals(forward, reverse);
        assertEquals(
                List.of("com.example:leaf:1.0.0:jar:compile"),
                forward.packages().getFirst().dependencies());
        assertEquals(2, forward.memberGraphs().size());
        LockMemberGraph apiGraph = graph(forward, "apps/api");
        assertEquals(List.of(), apiGraph.dependencies());
        assertEquals(List.of("edge-exclusion: com.example:leaf"), apiGraph.policies());
        assertEquals(
                List.of("com.example:leaf:1.0.0:jar:compile"),
                graph(forward, "apps/worker").dependencies());
    }

    private static LockMemberGraph graph(WorkspaceExternalSelection selection, String member) {
        return selection.memberGraphs().stream()
                .filter(graph -> graph.member().equals(member))
                .findFirst()
                .orElseThrow();
    }

    private static LockPackage candidate(
            String member,
            String jarHash,
            String pomHash,
            List<String> dependencies,
            List<String> policies) {
        return candidate(
                member,
                DependencyScope.COMPILE,
                jarHash,
                pomHash,
                dependencies,
                policies);
    }

    private static LockPackage candidate(
            String member,
            DependencyScope scope,
            String jarHash,
            String pomHash,
            List<String> dependencies,
            List<String> policies) {
        return new LockPackage(
                ROOT,
                "1.0.0",
                "member-" + member,
                scope,
                true,
                Optional.of("com/example/root/1.0.0/root-1.0.0.jar"),
                Optional.of("com/example/root/1.0.0/root-1.0.0.pom"),
                Optional.of(jarHash),
                Optional.of(pomHash),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dependencies,
                List.of(member),
                List.of(member),
                policies,
                List.of());
    }
}
