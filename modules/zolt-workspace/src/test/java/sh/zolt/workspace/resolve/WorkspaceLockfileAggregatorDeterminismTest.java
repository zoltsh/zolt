package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceProjectEdge;

final class WorkspaceLockfileAggregatorDeterminismTest
        extends WorkspaceLockfileAggregatorTestSupport {
    @Test
    void aggregatesWorkspaceAndExternalPackagesDeterministically()
            throws IOException {
        Workspace workspace = workspace(
                List.of(
                        new WorkspaceProjectEdge(
                                "apps/api",
                                "modules/core",
                                "compile",
                                "com.acme:core",
                                true),
                        new WorkspaceProjectEdge(
                                "apps/worker",
                                "modules/core",
                                "compile",
                                "com.acme:core"),
                        new WorkspaceProjectEdge(
                                "apps/api",
                                "modules/processor",
                                "processor",
                                "com.acme:processor")));
        PackageId library = new PackageId("com.example", "library");
        PackageId transitiveApi =
                new PackageId("com.example", "transitive-api");
        PackageId transitiveWorker =
                new PackageId("com.example", "transitive-worker");
        LockPolicyEffect policyEffect = new LockPolicyEffect(
                "allow",
                library,
                Optional.of("1.0.0"),
                Optional.of("central"),
                "enterprise-baseline");

        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace,
                List.of(
                        new WorkspaceMemberResolveOutput(
                                "apps/api",
                                lockfile(
                                        List.of(
                                                externalPackage(
                                                        library,
                                                        "2.0.0",
                                                        true,
                                                        List.of("com.example:transitive-api:0.9.0"),
                                                        List.of("api-policy")),
                                                externalPackage(
                                                        transitiveApi,
                                                        "0.9.0",
                                                        false,
                                                        List.of(),
                                                        List.of())),
                                        List.of(new LockConflict(
                                                library,
                                                "2.0.0",
                                                List.of("1.0.0", "2.0.0"),
                                                ConflictSelectionReason.DIRECT_DEPENDENCY)),
                                        List.of(policyEffect)),
                                Set.of(new WorkspaceExportedPackage(
                                        library,
                                        LockArtifactVariant.defaultVariant()))),
                        new WorkspaceMemberResolveOutput(
                                "apps/worker",
                                lockfile(
                                        List.of(
                                                externalPackage(
                                                        library,
                                                        "2.0.0",
                                                        true,
                                                        List.of("com.example:transitive-worker:1.0.0"),
                                                        List.of("worker-policy")),
                                                externalPackage(
                                                        transitiveWorker,
                                                        "1.0.0",
                                                        false,
                                                        List.of(),
                                                        List.of())),
                                        List.of(),
                                        List.of(policyEffect)),
                                Set.of())));

        assertEquals(
                List.of(
                        "com.acme:core:workspace:compile",
                        "com.acme:processor:workspace:processor",
                        "com.example:library:central:compile",
                        "com.example:transitive-api:central:compile",
                        "com.example:transitive-worker:central:compile"),
                aggregated.packages().stream()
                        .map(WorkspaceLockfileAggregatorDeterminismTest
                                ::packageSummary)
                        .toList());
        LockPackage core =
                packageById(aggregated, "com.acme", "core");
        assertEquals(List.of("apps/api", "apps/worker"), core.members());
        assertEquals(List.of("apps/api"), core.exportedBy());
        assertEquals("modules/core", core.workspace().orElseThrow());
        assertEquals(
                "target/classes", core.workspaceOutput().orElseThrow());
        LockPackage processor =
                packageById(aggregated, "com.acme", "processor");
        assertEquals(DependencyScope.PROCESSOR, processor.scope());
        assertEquals(List.of("apps/api"), processor.members());
        LockPackage external =
                packageById(aggregated, "com.example", "library");
        assertEquals("2.0.0", external.version());
        assertEquals(
                List.of(
                        "com.example:transitive-api:0.9.0",
                        "com.example:transitive-worker:1.0.0"),
                external.dependencies());
        assertEquals(
                List.of("apps/api", "apps/worker"),
                external.members());
        assertEquals(List.of("apps/api"), external.exportedBy());
        assertEquals(
                List.of("api-policy", "worker-policy"),
                external.policies());
        assertEquals(
                List.of(new LockConflict(
                        library,
                        "2.0.0",
                        List.of("1.0.0", "2.0.0"),
                        ConflictSelectionReason.DIRECT_DEPENDENCY,
                        Optional.empty(),
                        Optional.empty(),
                        List.of("apps/api"))),
                aggregated.conflicts());
        assertEquals(2, aggregated.memberGraphs().size());
        assertEquals(
                List.of(policyEffect), aggregated.policyEffects());
    }
}
