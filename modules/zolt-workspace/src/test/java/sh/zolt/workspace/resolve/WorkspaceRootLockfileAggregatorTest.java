package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;

final class WorkspaceRootLockfileAggregatorTest
        extends WorkspaceLockfileAggregatorTestSupport {
    @Test
    void enrichesTransitionalRootWorkspaceWithMemberQualifiedEvidence() {
        PackageId api = new PackageId("com.example", "api");
        PackageId optional = new PackageId("com.example", "optional");
        PackageId transitive =
                new PackageId("com.example", "transitive");
        ZoltLockfile memberLockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.of("sha256:project"),
                List.of("repositories=sha256:repo"),
                List.of(
                        externalPackage(
                                api,
                                "1.0.0",
                                true,
                                List.of("com.example:transitive:1.0.0"),
                                List.of()),
                        externalPackage(
                                optional,
                                "1.0.0",
                                true,
                                List.of(),
                                List.of()),
                        externalPackage(
                                transitive,
                                "1.0.0",
                                false,
                                List.of(),
                                List.of())),
                List.of(),
                List.of());
        Workspace workspace = new Workspace(
                Path.of("/repo"),
                Path.of("/repo/zolt.toml"),
                new WorkspaceConfig(
                        "zolt",
                        List.of("."),
                        List.of("."),
                        Map.of(),
                        Map.of()),
                List.of(new WorkspaceMember(
                        ".",
                        Path.of("/repo"),
                        config("app"))));

        ZoltLockfile aggregated =
                new WorkspaceLockfileAggregator().aggregate(
                        workspace,
                        List.of(new WorkspaceMemberResolveOutput(
                                ".",
                                memberLockfile,
                                Set.of(new WorkspaceExportedPackage(
                                        api,
                                        LockArtifactVariant.defaultVariant())),
                                Set.of(new WorkspaceOptionalPackage(
                                        optional,
                                        LockArtifactVariant.defaultVariant(),
                                        DependencyScope.COMPILE)),
                                Set.of(new WorkspaceOptionalPackage(
                                        optional,
                                        LockArtifactVariant.defaultVariant(),
                                        DependencyScope.COMPILE)))));

        assertNotSame(memberLockfile, aggregated);
        assertEquals(
                memberLockfile.projectResolutionFingerprint(),
                aggregated.projectResolutionFingerprint());
        assertEquals(
                memberLockfile.projectResolutionInputFingerprints(),
                aggregated.projectResolutionInputFingerprints());
        assertEquals(
                List.of(api, optional, transitive),
                aggregated.packages().stream()
                        .map(LockPackage::packageId)
                        .toList());
        assertTrue(aggregated.packages().stream()
                .allMatch(lockPackage ->
                        lockPackage.members().equals(List.of("."))));
        assertEquals(
                List.of("."),
                aggregated.packages().getFirst().exportedBy());
        assertEquals(
                List.of(),
                aggregated.packages().get(1).exportedBy());
        assertEquals(
                List.of(new LockMemberGraph(
                        ".",
                        optional,
                        "1.0.0",
                        LockArtifactVariant.defaultVariant(),
                        DependencyScope.COMPILE,
                        List.of(),
                        List.of(),
                        true,
                        true)),
                aggregated.memberGraphs().stream()
                        .filter(graph ->
                                graph.packageId().equals(optional))
                        .toList());
    }
}
