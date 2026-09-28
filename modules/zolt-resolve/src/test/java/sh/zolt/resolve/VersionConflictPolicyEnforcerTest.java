package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.project.LicensePolicySettings;
import sh.zolt.project.VersionConflictPolicy;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.lockfile.assembly.GroovyToolResolution;
import sh.zolt.resolve.version.VersionConflict;
import sh.zolt.resolve.version.VersionSelectionResult;
import org.junit.jupiter.api.Test;

final class VersionConflictPolicyEnforcerTest {
    @Test
    void historicalOnlyConflictIsAuditEvidenceNotAFailOnConflictViolation() {
        PackageId driver = new PackageId("com.example", "driver");
        VersionConflict historical = new VersionConflict(
                driver,
                LockArtifactVariant.defaultVariant(),
                List.of(
                        new DependencyRequest(
                                driver,
                                "1.0.0",
                                DependencyScope.COMPILE,
                                RequestOrigin.TRANSITIVE),
                        new DependencyRequest(
                                driver,
                                "3.0.0",
                                DependencyScope.COMPILE,
                                RequestOrigin.TRANSITIVE)),
                "1.0.0",
                ConflictSelectionReason.SELECTED_GRAPH,
                false);

        assertDoesNotThrow(() -> VersionConflictPolicyEnforcer.enforce(
                new DependencyPolicySettings(List.of(), Map.of(), true),
                new VersionSelectionResult(List.of(), List.of(historical)),
                List.of(),
                "zolt resolve"));
    }

    /**
     * Design §9.11: {@code warn} resolves and emits a structured warning, so it can behave neither like
     * {@code fail} (which rejects) nor like {@code resolve} (which says nothing).
     */
    @Test
    void warnPolicyMediatesAndReportsTheSameConflictsFailWouldHaveRejected() {
        VersionSelectionResult selection = new VersionSelectionResult(List.of(), List.of(activeConflict()));

        List<String> warnings = VersionConflictPolicyEnforcer.enforce(
                policy(VersionConflictPolicy.WARN), selection, List.of(), "zolt resolve");

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("[dependencies.policy].conflicts = \"warn\""), warnings.toString());
        assertTrue(warnings.getFirst().contains("com.example:driver"), warnings.toString());
        assertTrue(warnings.getFirst().contains("selected 3.0.0"), warnings.toString());
        assertTrue(warnings.getFirst().contains("1.0.0"), warnings.toString());

        assertTrue(VersionConflictPolicyEnforcer.enforce(
                        policy(VersionConflictPolicy.RESOLVE), selection, List.of(), "zolt resolve")
                .isEmpty());
        assertThrows(
                ResolveException.class,
                () -> VersionConflictPolicyEnforcer.enforce(
                        policy(VersionConflictPolicy.FAIL), selection, List.of(), "zolt resolve"));
    }

    @Test
    void enforcesGroovyCompilerClosureConflictsWithExplicitDiagnostics() {
        VersionSelectionResult selection =
                new VersionSelectionResult(List.of(), List.of(groovyConflict()));
        GroovyToolResolution resolution = new GroovyToolResolution(
                new ResolutionGraph(List.of(), List.of(), List.of(groovyConflict())),
                selection,
                List.of());

        List<String> warnings = VersionConflictPolicyEnforcer.enforce(
                policy(VersionConflictPolicy.WARN),
                new VersionSelectionResult(List.of(), List.of()),
                Optional.of(resolution),
                List.of(),
                "zolt resolve");

        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("Groovy compiler toolchain closure"), warnings.toString());

        ResolveException failure = assertThrows(
                ResolveException.class,
                () -> VersionConflictPolicyEnforcer.enforce(
                        policy(VersionConflictPolicy.FAIL),
                        new VersionSelectionResult(List.of(), List.of()),
                        Optional.of(resolution),
                        List.of(),
                        "zolt resolve"));
        assertTrue(failure.getMessage().contains("Groovy compiler toolchain closure"), failure.getMessage());
        assertTrue(
                failure.getMessage().contains("in the Groovy compiler toolchain"),
                failure.getMessage());
    }

    private static DependencyPolicySettings policy(VersionConflictPolicy conflicts) {
        return new DependencyPolicySettings(List.of(), Map.of(), conflicts, LicensePolicySettings.defaults());
    }

    private static VersionConflict activeConflict() {
        PackageId driver = new PackageId("com.example", "driver");
        return new VersionConflict(
                driver,
                LockArtifactVariant.defaultVariant(),
                List.of(
                        new DependencyRequest(driver, "1.0.0", DependencyScope.COMPILE, RequestOrigin.TRANSITIVE),
                        new DependencyRequest(driver, "3.0.0", DependencyScope.COMPILE, RequestOrigin.DIRECT)),
                "3.0.0",
                ConflictSelectionReason.DIRECT_DEPENDENCY,
                true);
    }

    private static VersionConflict groovyConflict() {
        PackageId groovy = new PackageId("org.apache.groovy", "groovy-helper");
        return new VersionConflict(
                groovy,
                LockArtifactVariant.defaultVariant(),
                List.of(
                        new DependencyRequest(
                                groovy,
                                "4.0.21",
                                DependencyScope.TOOL_GROOVY,
                                RequestOrigin.TRANSITIVE),
                        new DependencyRequest(
                                groovy,
                                "4.0.23",
                                DependencyScope.TOOL_GROOVY,
                                RequestOrigin.DIRECT)),
                "4.0.23",
                ConflictSelectionReason.DIRECT_DEPENDENCY,
                true);
    }
}
