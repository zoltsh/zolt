package sh.zolt.resolve.lockfile.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.DependencyPolicyEffect;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.graph.ResolutionEdge;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;
import sh.zolt.resolve.traversal.DependencyTraversalDecision;
import sh.zolt.resolve.version.VersionConflict;
import sh.zolt.resolve.version.VersionSelectionResult;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Assembler behaviour for isolated exec-tool closures (Hole 1): a shared library locked at divergent
 * versions stays split under separate tool groups, and every mediation/policy effect inside a tool closure
 * reaches the lockfile attributed to that tool, with the main graph left unattributed.
 */
final class LockfileAssemblerExecToolTest {
    private final LockfileAssembler assembler = new LockfileAssembler(new CoordinateParser());

    @Test
    void execToolsLockConflictingSharedLibraryUnderSeparateGroupsAndMergeCommonOnes() {
        PackageId shared = new PackageId("com.example", "shared");
        PackageId common = new PackageId("com.example", "common");
        PackageId alphaTool = new PackageId("com.example", "alpha");
        PackageId betaTool = new PackageId("com.example", "beta");
        PackageNode alphaNode = new PackageNode(alphaTool, "1.0.0");
        PackageNode betaNode = new PackageNode(betaTool, "1.0.0");
        PackageNode sharedV1 = new PackageNode(shared, "1.0.0");
        PackageNode sharedV2 = new PackageNode(shared, "2.0.0");
        PackageNode commonNode = new PackageNode(common, "1.0.0");

        ExecToolResolution alpha = new ExecToolResolution(
                "alpha",
                execGraph(alphaNode, sharedV1, commonNode),
                new VersionSelectionResult(List.of(alphaNode, sharedV1, commonNode), List.of()),
                List.of(new DependencyRequest(alphaTool, "1.0.0", DependencyScope.TOOL_EXEC, RequestOrigin.DIRECT)));
        ExecToolResolution beta = new ExecToolResolution(
                "beta",
                execGraph(betaNode, sharedV2, commonNode),
                new VersionSelectionResult(List.of(betaNode, sharedV2, commonNode), List.of()),
                List.of(new DependencyRequest(betaTool, "1.0.0", DependencyScope.TOOL_EXEC, RequestOrigin.DIRECT)));

        FakeAssemblyContext context = new FakeAssemblyContext(minimalConfig());
        ZoltLockfile lockfile = assembler.assemble(
                context,
                new ResolutionGraph(List.of(), List.of(), List.of()),
                new VersionSelectionResult(List.of(), List.of()),
                List.of(),
                List.of(alpha, beta));

        // The shared GA is locked at BOTH versions, each under its own tool group — never mediated to one.
        assertEquals(List.of("alpha"), findPackage(lockfile, shared, "1.0.0").toolGroups());
        assertEquals(List.of("beta"), findPackage(lockfile, shared, "2.0.0").toolGroups());
        assertEquals(DependencyScope.TOOL_EXEC, findPackage(lockfile, shared, "1.0.0").scope());
        // Each tool jar carries its own group; a jar shared at the same version unions the groups.
        assertEquals(List.of("alpha"), findPackage(lockfile, alphaTool, "1.0.0").toolGroups());
        assertEquals(List.of("beta"), findPackage(lockfile, betaTool, "1.0.0").toolGroups());
        assertEquals(List.of("alpha", "beta"), findPackage(lockfile, common, "1.0.0").toolGroups());
    }

    @Test
    void mergesExecToolConflictsAndPolicyEffectsWithToolAttributionLeavingMainUnattributed() {
        PackageId alphaTool = new PackageId("com.example", "alpha");
        PackageId betaTool = new PackageId("com.example", "beta");
        PackageId shared = new PackageId("com.example", "shared");
        PackageId other = new PackageId("com.example", "other");
        PackageNode alphaNode = new PackageNode(alphaTool, "1.0.0");
        PackageNode betaNode = new PackageNode(betaTool, "1.0.0");

        VersionConflict alphaConflict = new VersionConflict(
                shared,
                List.of(
                        new DependencyRequest(shared, "1.0.0", DependencyScope.TOOL_EXEC, RequestOrigin.TRANSITIVE),
                        new DependencyRequest(shared, "2.0.0", DependencyScope.TOOL_EXEC, RequestOrigin.TRANSITIVE)),
                "2.0.0",
                ConflictSelectionReason.NEWEST_VERSION);
        DependencyPolicyEffect alphaEffect = new DependencyPolicyEffect(
                "managed-version",
                shared,
                Optional.of("1.0.0"),
                Optional.of("com.example:alpha:1.0.0"),
                "managed-version: com.example:shared -> 2.0.0 from alpha-bom");
        ExecToolResolution alpha = new ExecToolResolution(
                "alpha",
                new ResolutionGraph(List.of(alphaNode), List.of(), List.of(alphaConflict), List.of(alphaEffect)),
                new VersionSelectionResult(List.of(alphaNode), List.of(alphaConflict)),
                List.of(new DependencyRequest(alphaTool, "1.0.0", DependencyScope.TOOL_EXEC, RequestOrigin.DIRECT)));

        VersionConflict betaConflict = new VersionConflict(
                other,
                List.of(
                        new DependencyRequest(other, "1.0.0", DependencyScope.TOOL_EXEC, RequestOrigin.TRANSITIVE),
                        new DependencyRequest(other, "3.0.0", DependencyScope.TOOL_EXEC, RequestOrigin.TRANSITIVE)),
                "3.0.0",
                ConflictSelectionReason.NEWEST_VERSION);
        DependencyPolicyEffect betaEffect = new DependencyPolicyEffect(
                "strict-version",
                other,
                Optional.of("1.0.0"),
                Optional.of("com.example:beta:1.0.0"),
                "strict-version: com.example:other -> 3.0.0");
        ExecToolResolution beta = new ExecToolResolution(
                "beta",
                new ResolutionGraph(List.of(betaNode), List.of(), List.of(betaConflict), List.of(betaEffect)),
                new VersionSelectionResult(List.of(betaNode), List.of(betaConflict)),
                List.of(new DependencyRequest(betaTool, "1.0.0", DependencyScope.TOOL_EXEC, RequestOrigin.DIRECT)));

        // A main-graph conflict must still be recorded exactly as before, with no tool attribution.
        PackageId mainLib = new PackageId("com.example", "main-lib");
        PackageNode mainNode = new PackageNode(mainLib, "2.0.0");
        VersionConflict mainConflict = new VersionConflict(
                mainLib,
                List.of(
                        new DependencyRequest(mainLib, "1.0.0", DependencyScope.COMPILE, RequestOrigin.TRANSITIVE),
                        new DependencyRequest(mainLib, "2.0.0", DependencyScope.COMPILE, RequestOrigin.DIRECT)),
                "2.0.0",
                ConflictSelectionReason.DIRECT_DEPENDENCY);

        // Pass the tools unsorted (beta first) to prove the assembler orders them deterministically.
        ZoltLockfile lockfile = assembler.assemble(
                new FakeAssemblyContext(minimalConfig()),
                new ResolutionGraph(List.of(mainNode), List.of(), List.of(mainConflict)),
                new VersionSelectionResult(List.of(mainNode), List.of(mainConflict)),
                List.of(new DependencyRequest(mainLib, "2.0.0", DependencyScope.COMPILE, RequestOrigin.DIRECT)),
                List.of(beta, alpha));

        // Every closure's mediation survives as a distinct, correctly attributed entry.
        assertEquals(3, lockfile.conflicts().size());
        assertEquals(Optional.empty(), conflictFor(lockfile, mainLib).toolGroup());
        assertEquals(Optional.of("alpha"), conflictFor(lockfile, shared).toolGroup());
        assertEquals(Optional.of("beta"), conflictFor(lockfile, other).toolGroup());
        assertEquals("2.0.0", conflictFor(lockfile, shared).selectedVersion());
        // Each tool's graph policy effects merge into the aggregate audit list.
        assertTrue(lockfile.policyEffects().stream().anyMatch(effect ->
                effect.packageId().equals(shared) && "managed-version".equals(effect.kind())));
        assertTrue(lockfile.policyEffects().stream().anyMatch(effect ->
                effect.packageId().equals(other) && "strict-version".equals(effect.kind())));
    }

    private static LockConflict conflictFor(ZoltLockfile lockfile, PackageId packageId) {
        return lockfile.conflicts().stream()
                .filter(conflict -> conflict.packageId().equals(packageId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no conflict for " + packageId));
    }

    private static ResolutionGraph execGraph(PackageNode root, PackageNode... children) {
        List<PackageNode> nodes = new ArrayList<>();
        nodes.add(root);
        List<ResolutionEdge> edges = new ArrayList<>();
        for (PackageNode child : children) {
            nodes.add(child);
            edges.add(new ResolutionEdge(
                    root,
                    child,
                    new DependencyRequest(child.packageId(), child.selectedVersion(),
                            DependencyScope.TOOL_EXEC, RequestOrigin.TRANSITIVE),
                    DependencyTraversalDecision.include("tool-exec")));
        }
        return new ResolutionGraph(List.copyOf(nodes), List.copyOf(edges), List.of());
    }

    private static LockPackage findPackage(ZoltLockfile lockfile, PackageId packageId, String version) {
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(packageId)
                        && lockPackage.version().equals(version))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no lock package " + packageId + ":" + version));
    }

    private static ProjectConfig minimalConfig() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
    }
}
