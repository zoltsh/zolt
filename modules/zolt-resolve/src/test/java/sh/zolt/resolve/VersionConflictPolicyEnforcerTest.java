package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;
import java.util.Map;
import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;
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
}
