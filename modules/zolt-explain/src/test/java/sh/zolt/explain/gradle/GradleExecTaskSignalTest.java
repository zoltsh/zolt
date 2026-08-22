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

/** Verifies Gradle {@code Exec}/{@code JavaExec} tasks classify to {@code gradle.exec-*} signals. */
final class GradleExecTaskSignalTest {
    @TempDir
    private Path tempDir;

    private final GradleStaticProjectInspector inspector = new GradleStaticProjectInspector();

    @Test
    void singleCommandExecTaskBecomesMappableWarn() throws IOException {
        GradleInspectionResult result = inspect("""
                plugins { id 'java' }

                tasks.register('generateProto', Exec) {
                    commandLine 'protoc', '--java_out=build/generated', 'service.proto'
                }
                """);
        ExplainSignal signal = signal(result, "gradle.exec-mappable");
        assertEquals(ExplainSignal.Severity.WARN, signal.severity());
        assertFalse(result.signals().stream().anyMatch(candidate -> candidate.id().equals("gradle.exec-unmappable")),
                () -> result.signals().toString());

        MigrationReadinessFinding finding = GradleMigrationReadinessFindings.map(signal);
        assertEquals(MigrationReadinessCategory.PLANNED, finding.category());
        assertEquals("[generated.tools] exec step", finding.zoltPrimitive());
    }

    @Test
    void scriptedExecTaskBecomesUnmappableBlock() throws IOException {
        GradleInspectionResult result = inspect("""
                plugins { id 'java' }

                tasks.register('release', Exec) {
                    doFirst { println 'starting' }
                    commandLine 'sh', '-c', 'build && deploy'
                }
                """);
        ExplainSignal signal = signal(result, "gradle.exec-unmappable");
        assertEquals(ExplainSignal.Severity.BLOCK, signal.severity());

        MigrationReadinessFinding finding = GradleMigrationReadinessFindings.map(signal);
        assertEquals(MigrationReadinessCategory.BLOCKED, finding.category());
        assertEquals("[tasks.<id>] or CI", finding.zoltPrimitive());
    }

    @Test
    void javaExecTaskTypeIsRecognized() throws IOException {
        GradleInspectionResult result = inspect("""
                plugins { id 'java' }

                tasks.register('runTool', JavaExec) {
                    mainClass = 'com.example.Tool'
                    args '--out', 'build/gen'
                }
                """);
        assertTrue(signal(result, "gradle.exec-mappable").message().contains("JavaExec"),
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
