package sh.zolt.workspace.service.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.testruntime.TestRunResult;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.workspace.coverage.WorkspaceCoverageResult;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.test.WorkspaceTestCompileResult;
import sh.zolt.workspace.test.WorkspaceTestResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WorkspaceResultSummaryTest {
    @Test
    void buildResultAggregatesDiagnosticsAndFingerprintTimings() {
        WorkspaceBuildResult result = new WorkspaceBuildResult(
                Optional.of(new ResolveResult(2, 1, 0, Path.of("zolt.lock"))),
                List.of(
                        memberBuild(
                                "modules/core",
                                build(
                                        "modules/core",
                                        3,
                                        false,
                                        "compile-classpath-changed",
                                        new CompileDiagnostics(1, 2, 3, 4, 5, 6, 7, 8),
                                        2_000_000L,
                                        4_000_000L)),
                        memberBuild(
                                "apps/api",
                                build(
                                        "apps/api",
                                        2,
                                        true,
                                        "",
                                        new CompileDiagnostics(2, 3, 4, 5, 6, 7, 8, 9),
                                        3_000_000L,
                                        5_000_000L))));

        assertTrue(result.resolvedLockfile());
        assertEquals(5, result.sourceCount());
        assertEquals(3, result.compiledSourceCount());
        assertEquals(1, result.buildWaveCount());
        assertEquals(1, result.buildMaxWorkers());
        assertEquals(1, result.mainCompilationSkippedCount());
        assertEquals(1, result.mainCompilationExecutedCount());
        assertEquals(5_000_000L, result.mainFingerprintCheckNanos());
        assertEquals(9_000_000L, result.mainFingerprintWriteNanos());
        assertEquals(5, result.mainFingerprintCheckMillis());
        assertEquals(9, result.mainFingerprintWriteMillis());
        assertEquals(1, result.workspaceAbiInvalidationCount());
        assertEquals(
                new CompileDiagnostics(3, 5, 7, 9, 11, 13, 15, 17),
                result.mainCompileDiagnostics());
    }

    @Test
    void testResultAggregatesSelectedDependencyAndTestRuntimeDiagnostics() {
        WorkspaceBuildResult.MemberBuildResult coreBuild = memberBuild(
                "modules/core",
                build("modules/core", 1, false, "", CompileDiagnostics.legacy(1, false), 1_000_000L, 2_000_000L));
        WorkspaceBuildResult.MemberBuildResult apiBuild = memberBuild(
                "apps/api",
                build("apps/api", 2, true, "", CompileDiagnostics.legacy(2, true), 3_000_000L, 4_000_000L));
        TestSelection selection = TestSelection.fromCli(
                List.of("com.acme.ApiTest", "com.acme.ApiTest#runs"),
                List.of("*ApiTest"),
                List.of("fast"),
                List.of("slow"));
        TestRunResult apiTests = new TestRunResult(
                new TestCompileResult(
                        apiBuild.result(),
                        3,
                        0,
                        Path.of("apps/api/target/test-classes"),
                        "",
                        true,
                        5_000_000L,
                        6_000_000L),
                "tests skipped\n",
                TestRunResult.metrics("junit-console", 7, 2, 1, 10L, 20L),
                selection,
                new TestJvmArguments(List.of("-Xmx256m")),
                Optional.of(Path.of("target/test-reports/apps/api")));
        WorkspaceTestResult result = new WorkspaceTestResult(
                Optional.empty(),
                List.of(coreBuild, apiBuild),
                List.of(new WorkspaceTestResult.MemberTestRunResult("apps/api", apiTests)),
                3);

        assertEquals(3, result.totalMemberCount());
        assertEquals(2, result.includedMemberCount());
        assertEquals(1, result.selectedMemberCount());
        assertEquals(1, result.dependencyMemberCount());
        assertEquals(3, result.mainSourceCount());
        assertEquals(3, result.testSourceCount());
        assertEquals(1, result.mainCompilationSkippedCount());
        assertEquals(1, result.mainCompilationExecutedCount());
        assertEquals(1, result.testCompilationSkippedCount());
        assertEquals(0, result.testCompilationExecutedCount());
        assertEquals(4_000_000L, result.mainFingerprintCheckNanos());
        assertEquals(6_000_000L, result.mainFingerprintWriteNanos());
        assertEquals(5_000_000L, result.testFingerprintCheckNanos());
        assertEquals(6_000_000L, result.testFingerprintWriteNanos());
        assertEquals(4, result.mainFingerprintCheckMillis());
        assertEquals(6, result.mainFingerprintWriteMillis());
        assertEquals(5, result.testFingerprintCheckMillis());
        assertEquals(6, result.testFingerprintWriteMillis());
        assertEquals(7, result.testRuntimeClasspathEntryCount());
        assertEquals(2, result.testLauncherClasspathEntryCount());
        assertEquals(1, result.testDiscoveryScanRootCount());
        assertEquals(1, result.testClassSelectorCount());
        assertEquals(1, result.testMethodSelectorCount());
        assertEquals(1, result.testPatternCount());
        assertEquals(1, result.testIncludedTagCount());
        assertEquals(1, result.testExcludedTagCount());
    }

    @Test
    void compilationOutcomesSeparateSkippedRestoredAndExecutedMembers() {
        WorkspaceBuildResult.MemberBuildResult compiled = memberBuild(
                "modules/compiled",
                build("modules/compiled", 3, false, "", CompileDiagnostics.empty(), 0L, 0L));
        WorkspaceBuildResult.MemberBuildResult skipped = memberBuild(
                "modules/skipped",
                build("modules/skipped", 2, true, "", CompileDiagnostics.empty(), 0L, 0L));
        WorkspaceBuildResult.MemberBuildResult restored = memberBuild(
                "modules/restored",
                restoredBuild("modules/restored", 4, 7));
        List<WorkspaceBuildResult.MemberBuildResult> builds = List.of(compiled, skipped, restored);
        WorkspaceBuildResult buildResult = new WorkspaceBuildResult(Optional.empty(), builds);

        assertEquals(1, buildResult.mainCompilationExecutedCount());
        assertEquals(1, buildResult.mainCompilationSkippedCount());
        assertEquals(1, buildResult.mainCompilationRestoredCount());
        assertEquals(3, buildResult.compiledSourceCount());
        assertEquals(7, buildResult.mainRestoredClassCount());

        TestCompileResult compiledTests = testCompile(compiled.result(), "modules/compiled", "full", false);
        TestCompileResult skippedTests = testCompile(skipped.result(), "modules/skipped", "skipped", true);
        TestCompileResult restoredTests = testCompile(restored.result(), "modules/restored", "restored", false);
        List<WorkspaceTestCompileResult.MemberTestCompileResult> compiledMembers = List.of(
                new WorkspaceTestCompileResult.MemberTestCompileResult("modules/compiled", compiledTests),
                new WorkspaceTestCompileResult.MemberTestCompileResult("modules/skipped", skippedTests),
                new WorkspaceTestCompileResult.MemberTestCompileResult("modules/restored", restoredTests));
        WorkspaceTestCompileResult compileResult = new WorkspaceTestCompileResult(
                Optional.empty(), builds, compiledMembers, 3, 1);

        assertEquals(1, compileResult.mainCompilationExecutedCount());
        assertEquals(1, compileResult.mainCompilationSkippedCount());
        assertEquals(1, compileResult.testCompilationExecutedCount());
        assertEquals(1, compileResult.testCompilationSkippedCount());

        WorkspaceTestResult testResult = new WorkspaceTestResult(
                Optional.empty(),
                builds,
                List.of(
                        new WorkspaceTestResult.MemberTestRunResult(
                                "modules/compiled", new TestRunResult(compiledTests, "")),
                        new WorkspaceTestResult.MemberTestRunResult(
                                "modules/skipped", new TestRunResult(skippedTests, "")),
                        new WorkspaceTestResult.MemberTestRunResult(
                                "modules/restored", new TestRunResult(restoredTests, ""))),
                3);

        assertEquals(1, testResult.mainCompilationExecutedCount());
        assertEquals(1, testResult.mainCompilationSkippedCount());
        assertEquals(1, testResult.testCompilationExecutedCount());
        assertEquals(1, testResult.testCompilationSkippedCount());
    }

    @Test
    void coverageResultDefaultsOptionalsAndConvertsToTestSummary() {
        WorkspaceBuildResult.MemberBuildResult coreBuild = memberBuild(
                "modules/core",
                build("modules/core", 1, false, "", CompileDiagnostics.legacy(1, false), 1_000_000L, 2_000_000L));
        WorkspaceBuildResult.MemberBuildResult apiBuild = memberBuild(
                "apps/api",
                build("apps/api", 2, false, "", CompileDiagnostics.legacy(2, false), 3_000_000L, 4_000_000L));
        TestSelection selection = TestSelection.fromCli(
                List.of("com.acme.ApiTest"),
                List.of(),
                List.of("fast"),
                List.of());
        TestRunResult apiTests = new TestRunResult(
                new TestCompileResult(
                        apiBuild.result(),
                        3,
                        0,
                        Path.of("apps/api/target/test-classes"),
                        "",
                        false,
                        5_000_000L,
                        6_000_000L),
                "api coverage\n",
                TestRunResult.metrics("junit-console", 4, 1, 1, 30L, 40L),
                selection,
                TestJvmArguments.empty(),
                Optional.of(Path.of("target/coverage/test-reports/apps/api")));
        WorkspaceCoverageResult coverage = new WorkspaceCoverageResult(
                null,
                List.of(coreBuild, apiBuild),
                List.of(new WorkspaceCoverageResult.MemberCoverageRunResult("apps/api", apiTests)),
                "aggregate report\n",
                Path.of("target/coverage/jacoco.exec"),
                null,
                null,
                2,
                3);

        WorkspaceTestResult summary = coverage.testResult();

        assertFalse(coverage.resolvedLockfile());
        assertEquals(Optional.empty(), coverage.resolveResult());
        assertEquals(Optional.empty(), coverage.xmlReport());
        assertEquals(Optional.empty(), coverage.htmlDirectory());
        assertEquals(List.of(coreBuild, apiBuild), summary.builtMembers());
        assertEquals(List.of("apps/api"), summary.members().stream()
                .map(WorkspaceTestResult.MemberTestRunResult::member)
                .toList());
        assertEquals(2, summary.includedMemberCount());
        assertEquals(1, summary.selectedMemberCount());
        assertEquals(1, summary.dependencyMemberCount());
        assertEquals(3, summary.mainSourceCount());
        assertEquals(3, summary.testSourceCount());
        assertEquals(1, summary.testClassSelectorCount());
        assertEquals(1, summary.testIncludedTagCount());
    }

    private static WorkspaceBuildResult.MemberBuildResult memberBuild(String member, BuildResult buildResult) {
        return new WorkspaceBuildResult.MemberBuildResult(member, buildResult, emptyClasspaths(), List.of());
    }

    private static BuildResult build(
            String member,
            int sourceCount,
            boolean skipped,
            String fallbackReason,
            CompileDiagnostics diagnostics,
            long fingerprintCheckNanos,
            long fingerprintWriteNanos) {
        return new BuildResult(
                Optional.empty(),
                sourceCount,
                0,
                Path.of(member).resolve("target/classes"),
                "",
                skipped,
                skipped ? "skipped" : "incremental",
                fallbackReason,
                diagnostics,
                fingerprintCheckNanos,
                fingerprintWriteNanos,
                0,
                "");
    }

    private static BuildResult restoredBuild(String member, int sourceCount, int restoredClassCount) {
        return new BuildResult(
                Optional.empty(),
                sourceCount,
                0,
                Path.of(member).resolve("target/classes"),
                "",
                false,
                "restored",
                "",
                CompileDiagnostics.empty(),
                0L,
                0L,
                restoredClassCount,
                "restored");
    }

    private static TestCompileResult testCompile(
            BuildResult buildResult,
            String member,
            String mode,
            boolean skipped) {
        return new TestCompileResult(
                buildResult,
                1,
                0,
                Path.of(member).resolve("target/test-classes"),
                "",
                skipped,
                mode,
                "",
                CompileDiagnostics.empty(),
                0L,
                0L);
    }

    private static ClasspathSet emptyClasspaths() {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(empty, empty, empty, empty, empty, empty);
    }
}
