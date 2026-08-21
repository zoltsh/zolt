package sh.zolt.quality.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.quality.QualityCheckContext;
import sh.zolt.quality.QualityCheckResult;
import sh.zolt.quality.QualityCheckStatus;

final class ExecutionCoverageFloorQualityCheckTest {
    private final ExecutionEvidenceQualityCheck check = new ExecutionEvidenceQualityCheck();

    @TempDir
    private Path tempDir;

    @Test
    void passesWhenCoverageMeetsFloors() throws Exception {
        writeConfig("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [coverage]
                line = 88.0
                branch = 74.0
                """);
        writeCoverage(jacocoXml(90, 10, 80, 20));

        QualityCheckResult result = checkResult();
        assertEquals(QualityCheckStatus.PASSED, result.status());
    }

    @Test
    void failsWhenCoverageBelowFloors() throws Exception {
        writeConfig("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [coverage]
                line = 88.0
                branch = 74.0
                """);
        writeCoverage(jacocoXml(86, 14, 70, 30));

        QualityCheckResult result = checkResult();
        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertEquals("coverage-floors", result.subject());
        assertTrue(result.message().contains("line coverage 86.0% is below the configured floor 88.0%"),
                result.message());
        assertTrue(result.message().contains("branch coverage 70.0% is below the configured floor 74.0%"),
                result.message());
        assertFalse(result.nextStep().isBlank());
    }

    @Test
    void passesWhenNoFloorsConfigured() throws Exception {
        writeConfig("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        writeCoverage(jacocoXml(10, 90, 5, 95));

        QualityCheckResult result = checkResult();
        assertEquals(QualityCheckStatus.PASSED, result.status());
    }

    @Test
    void skipsFloorsWhenNoXmlReportPresent() throws Exception {
        writeConfig("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [coverage]
                line = 99.0
                """);
        Path coverageDir = tempDir.resolve("target/coverage");
        Files.createDirectories(coverageDir.resolve("html"));
        Files.writeString(coverageDir.resolve("jacoco.exec"), "exec");
        Files.writeString(coverageDir.resolve("html/index.html"), "<html></html>");

        QualityCheckResult result = checkResult();
        assertEquals(QualityCheckStatus.PASSED, result.status());
    }

    private QualityCheckResult checkResult() {
        return check.checkCoverageReports(
                Optional.empty(),
                tempDir,
                Path.of("target/coverage"),
                Path.of("target/coverage"),
                Path.of("target"),
                QualityCheckContext.CI).getFirst();
    }

    private void writeConfig(String content) throws Exception {
        Files.writeString(tempDir.resolve("zolt.toml"), content);
    }

    private void writeCoverage(String jacocoXml) throws Exception {
        Path coverageDir = tempDir.resolve("target/coverage");
        Files.createDirectories(coverageDir);
        Files.writeString(coverageDir.resolve("jacoco.exec"), "exec");
        Files.writeString(coverageDir.resolve("jacoco.xml"), jacocoXml);
    }

    private static String jacocoXml(long lineCovered, long lineMissed, long branchCovered, long branchMissed) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="demo">
                  <counter type="LINE" missed="%d" covered="%d"/>
                  <counter type="BRANCH" missed="%d" covered="%d"/>
                </report>
                """.formatted(lineMissed, lineCovered, branchMissed, branchCovered);
    }
}
