package sh.zolt.resolve.lockfile.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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

final class LockfileAssemblerGroovyToolTest {
    private final LockfileAssembler assembler = new LockfileAssembler(new CoordinateParser());

    @Test
    void locksGroovyCompilerClosureIndependentlyWithoutExecToolAttribution() {
        PackageId groovy = new PackageId("org.apache.groovy", "groovy");
        PackageId helper = new PackageId("org.apache.groovy", "groovy-helper");
        PackageNode compileGroovy = new PackageNode(groovy, "4.0.22");
        PackageNode compilerGroovy = new PackageNode(groovy, "4.0.23");
        PackageNode compilerHelper = new PackageNode(helper, "4.0.23");

        DependencyRequest compilerRequest = new DependencyRequest(
                groovy, "4.0.23", DependencyScope.TOOL_GROOVY, RequestOrigin.DIRECT);
        DependencyRequest helperRequest = new DependencyRequest(
                helper, "4.0.23", DependencyScope.TOOL_GROOVY, RequestOrigin.TRANSITIVE);
        VersionConflict compilerConflict = new VersionConflict(
                groovy,
                List.of(
                        new DependencyRequest(
                                groovy,
                                "4.0.21",
                                DependencyScope.TOOL_GROOVY,
                                RequestOrigin.TRANSITIVE),
                        compilerRequest),
                "4.0.23",
                ConflictSelectionReason.DIRECT_DEPENDENCY);
        DependencyPolicyEffect compilerEffect = new DependencyPolicyEffect(
                "strict-version",
                helper,
                Optional.of("4.0.21"),
                Optional.of("org.apache.groovy:groovy:4.0.23"),
                "strict-version: org.apache.groovy:groovy-helper -> 4.0.23");
        ResolutionGraph compilerGraph = new ResolutionGraph(
                List.of(compilerGroovy, compilerHelper),
                List.of(new ResolutionEdge(
                        compilerGroovy,
                        compilerHelper,
                        helperRequest,
                        DependencyTraversalDecision.include("tool-groovy"))),
                List.of(compilerConflict),
                List.of(compilerEffect));
        GroovyToolResolution compilerResolution = new GroovyToolResolution(
                compilerGraph,
                new VersionSelectionResult(
                        List.of(compilerGroovy, compilerHelper),
                        List.of(compilerConflict)),
                List.of(compilerRequest));

        ZoltLockfile lockfile = assembler.assemble(
                new FakeAssemblyContext(minimalConfig()),
                new ResolutionGraph(List.of(compileGroovy), List.of(), List.of()),
                new VersionSelectionResult(List.of(compileGroovy), List.of()),
                List.of(new DependencyRequest(
                        groovy, "4.0.22", DependencyScope.COMPILE, RequestOrigin.DIRECT)),
                Optional.of(compilerResolution),
                List.of());

        LockPackage compileRow = findPackage(lockfile, groovy, "4.0.22", DependencyScope.COMPILE);
        LockPackage compilerRow = findPackage(lockfile, groovy, "4.0.23", DependencyScope.TOOL_GROOVY);
        LockPackage helperRow = findPackage(lockfile, helper, "4.0.23", DependencyScope.TOOL_GROOVY);
        assertTrue(compileRow.toolGroups().isEmpty());
        assertTrue(compilerRow.toolGroups().isEmpty());
        assertTrue(helperRow.toolGroups().isEmpty());
        assertEquals(
                List.of("org.apache.groovy:groovy-helper:4.0.23:jar:tool-groovy"),
                compilerRow.dependencies());
        assertTrue(compilerRow.direct());
        assertFalse(helperRow.direct());

        LockConflict conflict = lockfile.conflicts().getFirst();
        assertEquals(groovy, conflict.packageId());
        assertEquals(Optional.empty(), conflict.toolGroup());
        assertTrue(lockfile.policyEffects().stream().anyMatch(effect ->
                effect.packageId().equals(helper) && effect.kind().equals("strict-version")));
    }

    private static LockPackage findPackage(
            ZoltLockfile lockfile,
            PackageId packageId,
            String version,
            DependencyScope scope) {
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(packageId))
                .filter(lockPackage -> lockPackage.version().equals(version))
                .filter(lockPackage -> lockPackage.scope() == scope)
                .findFirst()
                .orElseThrow();
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
