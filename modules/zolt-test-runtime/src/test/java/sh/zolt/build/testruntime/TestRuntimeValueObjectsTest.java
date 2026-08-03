package sh.zolt.build.testruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.coverage.CoverageResult;
import sh.zolt.build.coverage.CoverageReportSettings;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.test.shard.TestShardSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestRuntimeValueObjectsTest {
    @TempDir
    private Path projectDir;

    @Test
    void testRunResultDefaultsNullableRuntimeDetails() {
        TestRunResult result = new TestRunResult(null, "ok\n", null, null, null, null, null);

        assertEquals("unknown", result.testRunner());
        assertEquals(0, result.testRuntimeClasspathEntries());
        assertEquals(0, result.testLauncherClasspathEntries());
        assertEquals(0, result.testDiscoveryScanRoots());
        assertEquals(0, result.testWorkerStarts());
        assertEquals(0, result.testWorkerRequests());
        assertEquals(-1L, result.testRunnerStartupNanos());
        assertEquals(-1L, result.testRunnerRequestNanos());
        assertTrue(result.testSelection().emptySelection());
        assertEquals(List.of(), result.testJvmArguments().values());
        assertTrue(result.reportsDirectory().isEmpty());
        assertTrue(result.profileDirectory().isEmpty());
    }

    @Test
    void testRunResultConstructorsPreserveOfficialMetricsShape() {
        TestRunResult legacy = new TestRunResult(null, "ok\n", 3, 2);
        TestRunResult withDiscovery = new TestRunResult(null, "ok\n", 4, 3, 2);
        TestRunResult withRunner = new TestRunResult(null, "ok\n", "zolt-junit-worker", 5, 4, 3);

        assertEquals("unknown", legacy.testRunner());
        assertEquals(3, legacy.testRuntimeClasspathEntries());
        assertEquals(2, legacy.testLauncherClasspathEntries());
        assertEquals(0, legacy.testDiscoveryScanRoots());
        assertEquals(2, withDiscovery.testDiscoveryScanRoots());
        assertEquals("zolt-junit-worker", withRunner.testRunner());
        assertEquals(5, withRunner.testRuntimeClasspathEntries());
        assertEquals(4, withRunner.testLauncherClasspathEntries());
        assertEquals(3, withRunner.testDiscoveryScanRoots());
    }

    @Test
    void testRunMetricsUsesUnknownRunnerForBlankValues() {
        TestRunMetrics metrics = new TestRunMetrics(" ", 1, 2, 3, 4L, 5L);

        assertEquals("unknown", metrics.testRunner());
        assertEquals(1, metrics.testRuntimeClasspathEntries());
        assertEquals(2, metrics.testLauncherClasspathEntries());
        assertEquals(3, metrics.testDiscoveryScanRoots());
        assertEquals(0, metrics.testWorkerStarts());
        assertEquals(0, metrics.testWorkerRequests());
        assertEquals(4L, metrics.testRunnerStartupNanos());
        assertEquals(5L, metrics.testRunnerRequestNanos());

        TestRunMetrics workerMetrics =
                new TestRunMetrics(
                        "zolt-junit-worker",
                        1,
                        2,
                        3,
                        4,
                        12,
                        5L,
                        6L);
        assertEquals(4, workerMetrics.testWorkerStarts());
        assertEquals(12, workerMetrics.testWorkerRequests());
    }

    @Test
    void reportSettingsDeriveWorkspaceAndShardEvidencePaths() {
        TestReportSettings reports = TestReportSettings.reportsDirectory(Path.of("target/test-reports"));

        assertEquals(
                Optional.of(Path.of("target/test-reports/modules/zolt-test-runtime")),
                reports.forWorkspaceMember("modules/zolt-test-runtime").reportsDirectory());
        assertTrue(TestReportSettings.disabled().forWorkspaceMember("modules/zolt-test-runtime").reportsDirectory().isEmpty());
        TestReportSettings shardReports = reports.forShard("fast suite!", new TestShardSpec(2, 3));
        assertEquals(
                Optional.of(Path.of("target/test-reports/shards/fast_suite_/shard-2-of-3")),
                shardReports.reportsDirectory());
        assertEquals(
                Optional.of(projectDir.resolve("target/test-reports/shards/fast_suite_/shard-2-of-3")
                        .toAbsolutePath()
                        .normalize()),
                shardReports.absoluteReportsDirectory(projectDir));
    }

    @Test
    void disabledReportSettingsStayInertForWorkspaceAndShardEvidence() {
        TestReportSettings disabled = new TestReportSettings(null);

        assertTrue(disabled.reportsDirectory().isEmpty());
        assertTrue(disabled.forWorkspaceMember("modules/zolt-test-runtime").reportsDirectory().isEmpty());
        assertEquals(disabled, disabled.forShard("fast", new TestShardSpec(1, 2)));
        assertTrue(disabled.projectRelativeReportsDirectory(projectDir).isEmpty());
        assertTrue(disabled.absoluteReportsDirectory(projectDir).isEmpty());
    }

    @Test
    void reportSettingsRejectOutputOutsideProjectWithRemediation() {
        TestRunException exception = assertThrows(
                TestRunException.class,
                () -> TestReportSettings.reportsDirectory(Path.of("../test-reports"))
                        .absoluteReportsDirectory(projectDir));

        assertTrue(exception.getMessage().contains("--reports-dir"));
        assertTrue(exception.getMessage().contains("Use a project-relative path"));
    }

    @Test
    void coverageResultDefaultsOptionalReports() {
        CoverageResult result = new CoverageResult(
                new TestRunResult(null, "ok\n"),
                "report\n",
                Path.of("target/coverage/jacoco.exec"),
                null,
                null);

        assertTrue(result.xmlReport().isEmpty());
        assertTrue(result.htmlDirectory().isEmpty());
    }

    @Test
    void coverageSettingsDefaultsNullablePathsAndReports() {
        CoverageReportSettings settings = new CoverageReportSettings(true, true, null, null, null, null);

        assertEquals(Path.of("target/coverage/jacoco.exec"), settings.execFile());
        assertEquals(Path.of("target/coverage/jacoco.xml"), settings.xmlReport());
        assertEquals(Path.of("target/coverage/html"), settings.htmlDirectory());
        assertEquals(Optional.of(Path.of("target/coverage/test-reports")), settings.testReports().reportsDirectory());
    }

    @Test
    void coverageSettingsForOutputRootUsesTargetFallbackAndCustomReportsDirectory() {
        CoverageReportSettings settings = CoverageReportSettings.forOutputRoot(
                true,
                false,
                null,
                null,
                null,
                null,
                Path.of("target/custom-test-reports"));

        assertEquals(Path.of("target/coverage/jacoco.exec"), settings.execFile());
        assertEquals(Path.of("target/coverage/jacoco.xml"), settings.xmlReport());
        assertEquals(Path.of("target/coverage/html"), settings.htmlDirectory());
        assertEquals(Optional.of(Path.of("target/custom-test-reports")), settings.testReports().reportsDirectory());
        assertEquals(Optional.of(projectDir.resolve("target/coverage/jacoco.xml").toAbsolutePath().normalize()),
                settings.absoluteXmlReport(projectDir));
        assertTrue(settings.absoluteHtmlDirectory(projectDir).isEmpty());
    }

    @Test
    void coverageShardPathsStayDeterministicWhenBasePathsHaveNoParent() {
        CoverageReportSettings settings = new CoverageReportSettings(
                true,
                true,
                Path.of("jacoco.exec"),
                Path.of("jacoco.xml"),
                Path.of("html"),
                TestReportSettings.disabled());

        CoverageReportSettings shardSettings = settings.forShard("fast suite!", new TestShardSpec(2, 3));

        Path shardRoot = Path.of("shards/fast_suite_/shard-2-of-3");
        assertEquals(shardRoot.resolve("jacoco.exec"), shardSettings.execFile());
        assertEquals(shardRoot.resolve("jacoco.xml"), shardSettings.xmlReport());
        assertEquals(shardRoot.resolve("html"), shardSettings.htmlDirectory());
        assertTrue(shardSettings.testReports().reportsDirectory().isEmpty());
    }

    @Test
    void testCompileResultNormalizesModesDiagnosticsAndTimings() {
        BuildResult buildResult = new BuildResult(Optional.empty(), 0, 0, Path.of("target/classes"), "");
        TestCompileResult result = new TestCompileResult(
                buildResult,
                2,
                1,
                Path.of("target/test-classes"),
                "",
                false,
                null,
                null,
                null,
                -2L,
                2_500_000L);
        TestCompileResult skipped = new TestCompileResult(
                buildResult,
                0,
                0,
                Path.of("target/test-classes"),
                "",
                true,
                "incremental",
                "stale fingerprint",
                new CompileDiagnostics(1, 2, 3, 4, 5, 6, 7, 8),
                4_000_000L,
                -4L);

        assertEquals("full", result.testCompilationMode());
        assertEquals("", result.testIncrementalFallbackReason());
        assertEquals(CompileDiagnostics.empty(), result.testCompileDiagnostics());
        assertEquals(0L, result.testFingerprintCheckNanos());
        assertEquals(2L, result.testFingerprintWriteMillis());
        assertEquals("skipped", skipped.testCompilationMode());
        assertEquals("stale fingerprint", skipped.testIncrementalFallbackReason());
        assertEquals(4L, skipped.testFingerprintCheckMillis());
        assertEquals(0L, skipped.testFingerprintWriteNanos());
    }

    @Test
    void testCompileResultLegacyConstructorsPreserveMetricsAndModes() {
        BuildResult buildResult = new BuildResult(Optional.empty(), 0, 0, Path.of("target/classes"), "");
        TestCompileResult full = new TestCompileResult(
                buildResult,
                3,
                1,
                Path.of("target/test-classes"),
                "compiled tests\n");
        TestCompileResult skipped = new TestCompileResult(
                buildResult,
                0,
                0,
                Path.of("target/test-classes"),
                "",
                true);
        TestCompileResult timed = new TestCompileResult(
                buildResult,
                1,
                0,
                Path.of("target/test-classes"),
                "",
                false,
                2_000_000L,
                3_000_000L);

        assertEquals("full", full.testCompilationMode());
        assertEquals(3, full.testCompileDiagnostics().sourcesRecompiled());
        assertEquals(1, full.resourceCount());
        assertEquals("skipped", skipped.testCompilationMode());
        assertEquals(0, skipped.testCompileDiagnostics().sourcesRecompiled());
        assertEquals(2L, timed.testFingerprintCheckMillis());
        assertEquals(3L, timed.testFingerprintWriteMillis());
    }

    @Test
    void testRunResultKeepsExplicitSelectionArgumentsAndReports() {
        TestSelection selection = TestSelection.fromCli(
                List.of("com.example.MainTest"),
                List.of(),
                List.of("fast"),
                List.of("slow"));
        TestJvmArguments jvmArguments = new TestJvmArguments(List.of("-Dmode=test"));

        TestRunResult result = new TestRunResult(
                null,
                "ok\n",
                TestRunResult.metrics("junit-console", 3, 2, 1, 10L, 20L),
                selection,
                jvmArguments,
                Optional.of(Path.of("target/test-reports")),
                Optional.of(Path.of("target/test-profile")));

        assertEquals(selection, result.testSelection());
        assertEquals(jvmArguments, result.testJvmArguments());
        assertEquals(Optional.of(Path.of("target/test-reports")), result.reportsDirectory());
        assertEquals(Optional.of(Path.of("target/test-profile")), result.profileDirectory());
    }

    @Test
    void testRunResultConvenienceConstructorsPreserveSelectionReportsAndDefaults() {
        TestSelection selection = TestSelection.fromCli(
                List.of("com.example.MainTest", "com.example.MainTest#runs"),
                List.of("*MainTest"),
                List.of(),
                List.of());
        TestJvmArguments jvmArguments = new TestJvmArguments(List.of("-Dsmoke=true"));
        TestRunMetrics metrics = TestRunResult.metrics("junit-console", 4, 2, 1, 30L, 40L);

        TestRunResult withSelection = new TestRunResult(null, "ok\n", metrics, selection);
        TestRunResult withReports = new TestRunResult(
                null,
                "ok\n",
                metrics,
                selection,
                jvmArguments,
                Optional.of(Path.of("target/test-reports")));

        assertEquals(selection, withSelection.testSelection());
        assertEquals(List.of(), withSelection.testJvmArguments().values());
        assertTrue(withSelection.reportsDirectory().isEmpty());
        assertEquals(selection, withReports.testSelection());
        assertEquals(jvmArguments, withReports.testJvmArguments());
        assertEquals(Optional.of(Path.of("target/test-reports")), withReports.reportsDirectory());
        assertTrue(withReports.profileDirectory().isEmpty());
    }
}
