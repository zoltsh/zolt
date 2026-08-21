package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.gradle.GradleInspectionResult;
import sh.zolt.explain.maven.MavenInspectionResult;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class InspectionToProjectConfigEdgeTest {
    private final InspectionToProjectConfig mapper = new InspectionToProjectConfig();

    @Test
    void emptyMavenAuditHasActionableEmitError() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> mapper.emitFromMaven(new MavenInspectionResult(Path.of("."), List.of(), List.of())));

        assertTrue(exception.getMessage().contains("the Maven audit found no project"), exception::getMessage);
        assertTrue(exception.getMessage().contains("Run zolt explain from a Maven project root."),
                exception::getMessage);
    }

    @Test
    void emptyGradleAuditHasActionableEmitError() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> mapper.emitFromGradle(new GradleInspectionResult(
                        Path.of("."),
                        "",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of())));

        assertTrue(exception.getMessage().contains("the Gradle audit found no project"), exception::getMessage);
        assertTrue(exception.getMessage().contains("Run zolt explain from a Gradle project root."),
                exception::getMessage);
    }
}
