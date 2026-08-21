package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceProjectEdge;

final class WorkspaceDependencyRootAggregatorTest extends WorkspaceLockfileAggregatorTestSupport {
    @Test
    void requalifiesMemberRootsAndAddsWorkspaceDeclarations() throws IOException {
        Workspace workspace = workspace(List.of(
                new WorkspaceProjectEdge(
                        "apps/api", "modules/core", "compile", "com.acme:core", true, true),
                new WorkspaceProjectEdge(
                        "apps/worker", "modules/processor", "test-processor", "com.acme:processor")));
        PackageId library = new PackageId("com.example", "library");
        LockPackage external = externalPackage(library, "1.0.0", true, List.of(), List.of());
        LockDependencyRoot externalRoot = new LockDependencyRoot(
                ".", library, "1.0.0", null, DependencyLane.IMPLEMENTATION,
                Optional.of(DependencyScope.COMPILE), false, false);
        LockDependencyRoot publishOnly = new LockDependencyRoot(
                ".", new PackageId("com.example", "published"), "2.0.0", null,
                DependencyLane.RUNTIME, Optional.empty(), false, true);
        ZoltLockfile memberLock = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(external),
                List.of(),
                List.of(),
                List.of(),
                List.of(externalRoot, publishOnly));

        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace,
                List.of(new WorkspaceMemberResolveOutput("apps/api", memberLock, Set.of())));

        assertTrue(aggregated.dependencyRoots().contains(new LockDependencyRoot(
                "apps/api", library, "1.0.0", null, DependencyLane.IMPLEMENTATION,
                Optional.of(DependencyScope.COMPILE), false, false)));
        assertTrue(aggregated.dependencyRoots().contains(new LockDependencyRoot(
                "apps/api", new PackageId("com.example", "published"), "2.0.0", null,
                DependencyLane.RUNTIME, Optional.empty(), false, true)));
        assertTrue(aggregated.dependencyRoots().contains(new LockDependencyRoot(
                "apps/api", new PackageId("com.acme", "core"), "0.1.0", null,
                DependencyLane.API, Optional.of(DependencyScope.COMPILE), true, false)));
        assertTrue(aggregated.dependencyRoots().contains(new LockDependencyRoot(
                "apps/worker", new PackageId("com.acme", "processor"), "0.1.0", null,
                DependencyLane.TEST_PROCESSOR, Optional.of(DependencyScope.TEST_PROCESSOR), false, false)));
        assertEquals(4, aggregated.dependencyRoots().size());
    }

    @Test
    void rejectsAlreadyQualifiedMemberRootsInsteadOfRewritingProvenance() throws IOException {
        PackageId library = new PackageId("com.example", "library");
        LockPackage external = externalPackage(
                library, "1.0.0", true, List.of(), List.of(), List.of("other/member"));
        LockDependencyRoot qualifiedRoot = new LockDependencyRoot(
                "other/member", library, "1.0.0", null, DependencyLane.IMPLEMENTATION,
                Optional.of(DependencyScope.COMPILE), false, false);
        ZoltLockfile memberLock = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(external),
                List.of(),
                List.of(),
                List.of(),
                List.of(qualifiedRoot));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new WorkspaceLockfileAggregator().aggregate(
                        workspace(List.of()),
                        List.of(new WorkspaceMemberResolveOutput("apps/api", memberLock, Set.of()))));

        assertTrue(failure.getMessage().contains("must use member `.`"));
        assertTrue(failure.getMessage().contains("other/member"));
    }
}
