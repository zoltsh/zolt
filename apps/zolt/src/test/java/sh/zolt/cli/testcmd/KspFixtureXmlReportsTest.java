package sh.zolt.cli.testcmd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.EngineFilter.includeEngines;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener;

/** Exercises this member's test.runtime configuration with the real KSP fixture classpath. */
final class KspFixtureXmlReportsTest {
    @TempDir Path reports;

    @Test
    void junitWritesParseableXmlWithKspFixturesOnTheTestClasspath() throws Exception {
        assertNotNull(getClass().getClassLoader().getResource(
                "com/google/devtools/ksp/cmdline/KSPJvmMain.class"));
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(ReportFixture.class))
                .filters(includeEngines("junit-jupiter"))
                .build();
        var summary = new SummaryGeneratingListener();
        var diagnostics = new StringWriter();
        var xml = new LegacyXmlReportGeneratingListener(reports, new PrintWriter(diagnostics, true));

        LauncherFactory.create().execute(request, summary, xml);

        assertEquals(1, summary.getSummary().getTestsSucceededCount());
        Path report = reports.resolve("TEST-junit-jupiter.xml");
        assertTrue(Files.isRegularFile(report) && Files.size(report) > 0, diagnostics.toString());
        var document = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder().parse(report.toFile());
        assertEquals("testsuite", document.getDocumentElement().getTagName());
        assertEquals("1", document.getDocumentElement().getAttribute("tests"));
        assertEquals("0", document.getDocumentElement().getAttribute("failures"));
        assertEquals(1, document.getElementsByTagName("testcase").getLength());
    }

    static final class ReportFixture {
        @Test
        void passes() {}
    }
}
