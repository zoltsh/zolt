package sh.zolt.explain.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.ExplainSignal;
import sh.zolt.explain.MigrationReadinessCategory;
import sh.zolt.explain.MigrationReadinessFinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies a Gradle {@code java-platform} project classifies to the {@code gradle.bom.detected} signal. */
final class GradleJavaPlatformBomSignalTest {
    @TempDir
    private Path tempDir;

    private final GradleStaticProjectInspector inspector = new GradleStaticProjectInspector();

    @Test
    void javaPlatformProjectBecomesBomDetectedOk() throws IOException {
        GradleInspectionResult result = inspect("""
                plugins {
                    id 'java-platform'
                }
                dependencies {
                    api platform('com.fasterxml.jackson:jackson-bom:2.18.2')
                    constraints {
                        api 'com.example:lib-a:1.2.0'
                    }
                }
                """);

        ExplainSignal signal = signal(result, "gradle.bom.detected");
        assertEquals(ExplainSignal.Severity.OK, signal.severity());
        assertTrue(signal.message().contains("java-platform BOM detected"), () -> signal.message());
        assertTrue(signal.message().contains("1 version constraint(s)")
                        && signal.message().contains("1 platform import(s)"),
                () -> signal.message());

        MigrationReadinessFinding finding = GradleMigrationReadinessFindings.map(signal);
        assertEquals(MigrationReadinessCategory.PLANNED, finding.category());
    }

    @Test
    void plainJavaProjectHasNoBomSignal() throws IOException {
        GradleInspectionResult result = inspect("""
                plugins { id 'java' }
                dependencies {
                    implementation 'org.slf4j:slf4j-api:2.0.16'
                }
                """);

        assertFalse(result.signals().stream().anyMatch(signal -> signal.id().equals("gradle.bom.detected")),
                () -> result.signals().toString());
    }

    @Test
    void unappliedJavaPlatformPluginHasNoBomSignal() throws IOException {
        GradleInspectionResult result = inspect("""
                plugins {
                    id 'java'
                    id 'java-platform' apply false
                }
                """);

        assertFalse(result.signals().stream().anyMatch(signal -> signal.id().equals("gradle.bom.detected")),
                () -> result.signals().toString());
    }

    private GradleInspectionResult inspect(String buildGradle) throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'demo'\n");
        Files.writeString(tempDir.resolve("build.gradle"), buildGradle);
        return inspector.inspect(tempDir);
    }

    private static ExplainSignal signal(GradleInspectionResult result, String id) {
        return result.signals().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing signal " + id + " in " + result.signals()));
    }
}
