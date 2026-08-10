package sh.zolt.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class QualityCheckFormatterTest {
    @Test
    void jsonEscapesControlCharactersAndListsOnlyFailedBlockers() {
        QualityCheckReport report = new QualityCheckReport(
                Path.of("demo"),
                false,
                List.of(
                        QualityCheckResult.passed(
                                "format",
                                Optional.empty(),
                                "demo",
                                "message with \"quote\" and newline\ninside"),
                        QualityCheckResult.failed(
                                "blocked",
                                Optional.of("modules/api"),
                                "subject\\path",
                                "tab\tbackspace\bformfeed\fcarriage\r",
                                "fix \"now\"")));

        String json = QualityCheckFormatter.json(report);

        assertTrue(json.startsWith("{\"schemaVersion\":1,\"command\":\"check\",\"status\":\"failed\""));
        assertTrue(json.contains("\"workspace\":false"));
        assertTrue(json.contains("\"message\":\"message with \\\"quote\\\" and newline\\ninside\""));
        assertTrue(json.contains("\"subject\":\"subject\\\\path\""));
        assertTrue(json.contains("\"message\":\"tab\\tbackspace\\bformfeed\\fcarriage\\r\""));
        assertTrue(json.contains("\"member\":\"modules/api\""));
        assertTrue(json.contains("\"blockers\":[{\"id\":\"blocked\""));
        assertTrue(json.contains("\"diagnostics\":[{\"id\":\"blocked\""));
        assertFalse(json.contains("\"blockers\":[{\"id\":\"format\""));
        assertTrue(json.endsWith(System.lineSeparator()));
    }

    @Test
    void jsonEscapesLowControlCharactersAndKeepsInputOrder() {
        QualityCheckReport report = new QualityCheckReport(
                Path.of("demo"),
                false,
                List.of(
                        QualityCheckResult.failed(
                                "first",
                                Optional.empty(),
                                "subject",
                                "low \u0001 control",
                                "fix first"),
                        QualityCheckResult.failed(
                                "second",
                                Optional.empty(),
                                "subject",
                                "second",
                                "fix second")));

        String json = QualityCheckFormatter.json(report);

        assertTrue(json.contains("\"message\":\"low \\u0001 control\""));
        assertTrue(json.indexOf("\"id\":\"first\"") < json.indexOf("\"id\":\"second\""));
        int blockersStart = json.indexOf("\"blockers\"");
        assertTrue(json.indexOf("{\"id\":\"first\"", blockersStart) < json.indexOf("{\"id\":\"second\"", blockersStart));
    }

    @Test
    void textOmitsBlankNextStepAndKeepsMemberPrefixDeterministic() {
        QualityCheckReport report = new QualityCheckReport(
                Path.of("demo"),
                true,
                List.of(
                        QualityCheckResult.passed("one", Optional.empty(), "root", "ok"),
                        QualityCheckResult.warning("two", Optional.of("modules/api"), "subject", "warn", "Do this.")));

        assertEquals(
                "ok one root ok" + System.lineSeparator()
                        + "warning two modules/api subject warn" + System.lineSeparator()
                        + "  next: Do this." + System.lineSeparator(),
                QualityCheckFormatter.text(report));
    }

    @Test
    void reportDefensivelyCopiesChecksAndCountsStatuses() {
        List<QualityCheckResult> checks = new ArrayList<>();
        checks.add(QualityCheckResult.passed("pass", Optional.empty(), "subject", "message"));
        checks.add(QualityCheckResult.failed("fail", Optional.empty(), "subject", "message", "next"));
        checks.add(QualityCheckResult.skipped("skip", Optional.empty(), "subject", "message", "next"));

        QualityCheckReport report = new QualityCheckReport(Path.of("demo/..").resolve("demo"), false, checks);
        checks.clear();

        assertEquals("error", report.status());
        assertFalse(report.ok());
        assertEquals(1, report.passedCount());
        assertEquals(1, report.failedCount());
        assertEquals(1, report.skippedCount());
        assertEquals(3, report.checks().size());
        assertThrows(UnsupportedOperationException.class, () -> report.checks().add(
                QualityCheckResult.passed("new", Optional.empty(), "subject", "message")));
    }

    @Test
    void contextParsingIsCaseAndWhitespaceInsensitive() {
        assertEquals(Optional.of(QualityCheckContext.CI), QualityCheckContext.fromConfigValue(" CI "));
        assertEquals(Optional.of(QualityCheckContext.LOCAL), QualityCheckContext.fromConfigValue("local"));
        assertEquals(Optional.empty(), QualityCheckContext.fromConfigValue("release"));
        assertEquals(Optional.empty(), QualityCheckContext.fromConfigValue(" "));
        assertEquals(Optional.empty(), QualityCheckContext.fromConfigValue(null));
    }

    @Test
    void displayPathRelativizesOnlyPathsInsideRoot() {
        Path root = Path.of("/tmp/demo").toAbsolutePath().normalize();

        assertEquals("target/report.xml", QualityCheckText.displayPath(root, root.resolve("target/report.xml")));
        assertEquals(
                Path.of("/tmp/other/report.xml").toAbsolutePath().normalize().toString(),
                QualityCheckText.displayPath(root, Path.of("/tmp/other/report.xml")));
        assertEquals("dependency", QualityCheckText.plural(1, "dependency", "dependencies"));
        assertEquals("dependencies", QualityCheckText.plural(2, "dependency", "dependencies"));
        assertEquals("uses", QualityCheckText.verb(1, "uses", "use"));
        assertEquals("use", QualityCheckText.verb(2, "uses", "use"));
    }
}
