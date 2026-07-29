package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorkspaceBuildRequirementResolverTest {
    @Test
    void ordinaryTestCompilationDoesNotRequestPackageMetadata() {
        assertFalse(WorkspaceBuildRequirements.testCompile().packageInputs());
        assertFalse(WorkspaceBuildRequirements.testRun().packageInputs());
    }

    @Test
    void packagingAlwaysRequestsPackageMetadata() {
        WorkspaceBuildRequirements requirements =
                WorkspaceBuildRequirements.packaging();

        assertTrue(requirements.packageInputs());
        assertSame(requirements, requirements.withPackageInputs(true));
    }

    @Test
    void packageBackedGeneratorsRequestPackageMetadata() {
        assertTrue(WorkspaceBuildRequirementResolver.requiresPackageInputs(
                List.of(step(GeneratedSourceKind.OPENAPI))));
        assertTrue(WorkspaceBuildRequirementResolver.requiresPackageInputs(
                List.of(step(GeneratedSourceKind.EXEC))));
        assertFalse(WorkspaceBuildRequirementResolver.requiresPackageInputs(
                List.of(step(GeneratedSourceKind.PROTOBUF))));
        assertFalse(WorkspaceBuildRequirementResolver.requiresPackageInputs(
                List.of(step(GeneratedSourceKind.DECLARED_ROOT))));
    }

    private static GeneratedSourceStep step(GeneratedSourceKind kind) {
        return new GeneratedSourceStep(
                "sample",
                kind,
                "java",
                "target/generated/sample",
                List.of(),
                false,
                false);
    }
}
