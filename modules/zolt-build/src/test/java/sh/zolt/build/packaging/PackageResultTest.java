package sh.zolt.build.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.project.PackageMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PackageResultTest {
    private static final Path CLASSES = Path.of("target/classes");
    private static final Path ARCHIVE = Path.of("target/demo-0.1.0.jar");

    @Test
    void defaultsNullableEvidenceFieldsToThinArchiveRoot() {
        PackageResult result = new PackageResult(
                buildResult(),
                null,
                ARCHIVE,
                null,
                null,
                3,
                true,
                " ",
                null,
                null);

        assertEquals(PackageMode.THIN, result.mode());
        assertEquals(Optional.empty(), result.runtimeClasspathPath());
        assertEquals(Optional.empty(), result.evidenceManifestPath());
        assertEquals("archive root", result.applicationLayout());
        assertTrue(result.artifacts().isEmpty());
        assertTrue(result.mergeDecisions().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.artifacts().add(artifact("sources")));
        assertThrows(UnsupportedOperationException.class, () -> result.mergeDecisions().add(mergeDecision()));
    }

    @Test
    void usesModeSpecificDefaultApplicationLayouts() {
        assertEquals("archive root", resultFor(PackageMode.UBER, null).applicationLayout());
        assertEquals("BOOT-INF/classes", resultFor(PackageMode.SPRING_BOOT, "").applicationLayout());
        assertEquals("WEB-INF/classes", resultFor(PackageMode.WAR, " ").applicationLayout());
        assertEquals("WEB-INF/classes", resultFor(PackageMode.SPRING_BOOT_WAR, null).applicationLayout());
        assertEquals("framework package output", resultFor(PackageMode.QUARKUS, null).applicationLayout());
    }

    @Test
    void withMethodsDefensivelyReplaceEvidenceAndPreservePipelineMetadata() {
        PackageResult original = new PackageResult(buildResult(), ARCHIVE, Optional.empty(), 3, true);
        List<PackageArtifact> artifacts = new ArrayList<>(List.of(artifact("sources")));
        Path evidence = Path.of("target/demo-0.1.0.jar.zolt-package.json");

        PackageResult withEvidence = original.withArtifactsAndEvidence(artifacts, Optional.of(evidence));
        artifacts.add(artifact("javadoc"));
        PackageResult withLayout = withEvidence.withApplicationLayout("custom/classes");
        PackageResult withMergeDecisions = withLayout.withMergeDecisions(new ArrayList<>(List.of(mergeDecision())));

        assertEquals(List.of(artifact("sources")), withEvidence.artifacts());
        assertEquals(Optional.of(evidence), withLayout.evidenceManifestPath());
        assertEquals("custom/classes", withLayout.applicationLayout());
        assertEquals("custom/classes", withMergeDecisions.applicationLayout());
        assertEquals(List.of(mergeDecision()), withMergeDecisions.mergeDecisions());
        assertThrows(UnsupportedOperationException.class, () -> withEvidence.artifacts().add(artifact("extra")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> withMergeDecisions.mergeDecisions().add(mergeDecision()));
    }

    @Test
    void withMethodsPreserveReuseStatus() {
        PackageResult reused = new PackageResult(
                buildResult(),
                PackageMode.THIN,
                ARCHIVE,
                Optional.empty(),
                Optional.empty(),
                3,
                true,
                "archive root",
                List.of(),
                List.of(),
                List.of(),
                true);

        assertTrue(reused.withArtifactsAndEvidence(List.of(), Optional.empty()).packagingReused());
        assertTrue(reused.withApplicationLayout("classes").packagingReused());
        assertTrue(reused.withMergeDecisions(List.of()).packagingReused());
        assertTrue(reused.withMaterializedInputs(List.of()).packagingReused());
    }

    private static PackageResult resultFor(PackageMode mode, String applicationLayout) {
        return new PackageResult(
                buildResult(),
                mode,
                ARCHIVE,
                Optional.empty(),
                Optional.empty(),
                0,
                false,
                applicationLayout,
                List.of(),
                List.of());
    }

    private static BuildResult buildResult() {
        return new BuildResult(Optional.empty(), 1, 0, CLASSES, "");
    }

    private static PackageArtifact artifact(String classifier) {
        return new PackageArtifact(classifier, Path.of("target/demo-0.1.0-" + classifier + ".jar"), 1);
    }

    private static PackageMergeDecision mergeDecision() {
        return new PackageMergeDecision(
                "service-descriptor",
                "META-INF/services/com.example.Plugin",
                Optional.empty(),
                List.of("lib/a.jar", "lib/b.jar"));
    }
}
