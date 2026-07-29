package sh.zolt.build.testruntime;

import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.TestSelection;
import java.nio.file.Path;
import java.util.Optional;

public record TestRunResult(
        TestCompileResult compileResult,
        String output,
        TestRunMetrics metrics,
        TestSelection testSelection,
        TestJvmArguments testJvmArguments,
        Optional<Path> reportsDirectory,
        Optional<Path> profileDirectory) {
    public TestRunResult {
        metrics = metrics == null ? TestRunMetrics.unknown() : metrics;
        testSelection = testSelection == null ? TestSelection.empty() : testSelection;
        testJvmArguments = testJvmArguments == null ? TestJvmArguments.empty() : testJvmArguments;
        reportsDirectory = reportsDirectory == null ? Optional.empty() : reportsDirectory;
        profileDirectory = profileDirectory == null ? Optional.empty() : profileDirectory;
    }

    public TestRunResult(TestCompileResult compileResult, String output) {
        this(
                compileResult,
                output,
                TestRunMetrics.unknown(),
                TestSelection.empty(),
                TestJvmArguments.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public TestRunResult(
            TestCompileResult compileResult,
            String output,
            int testRuntimeClasspathEntries,
            int testLauncherClasspathEntries) {
        this(compileResult, output, metrics(
                "unknown",
                testRuntimeClasspathEntries,
                testLauncherClasspathEntries,
                0,
                -1L,
                -1L));
    }

    public TestRunResult(
            TestCompileResult compileResult,
            String output,
            int testRuntimeClasspathEntries,
            int testLauncherClasspathEntries,
            int testDiscoveryScanRoots) {
        this(compileResult, output, metrics(
                "unknown",
                testRuntimeClasspathEntries,
                testLauncherClasspathEntries,
                testDiscoveryScanRoots,
                -1L,
                -1L));
    }

    public TestRunResult(
            TestCompileResult compileResult,
            String output,
            String testRunner,
            int testRuntimeClasspathEntries,
            int testLauncherClasspathEntries,
            int testDiscoveryScanRoots) {
        this(compileResult, output, metrics(
                testRunner,
                testRuntimeClasspathEntries,
                testLauncherClasspathEntries,
                testDiscoveryScanRoots,
                -1L,
                -1L));
    }

    public TestRunResult(
            TestCompileResult compileResult,
            String output,
            TestRunMetrics metrics) {
        this(
                compileResult,
                output,
                metrics,
                TestSelection.empty(),
                TestJvmArguments.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public TestRunResult(
            TestCompileResult compileResult,
            String output,
            TestRunMetrics metrics,
            TestSelection testSelection) {
        this(
                compileResult,
                output,
                metrics,
                testSelection,
                TestJvmArguments.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public TestRunResult(
            TestCompileResult compileResult,
            String output,
            TestRunMetrics metrics,
            TestSelection testSelection,
            TestJvmArguments testJvmArguments,
            Optional<Path> reportsDirectory) {
        this(
                compileResult,
                output,
                metrics,
                testSelection,
                testJvmArguments,
                reportsDirectory,
                Optional.empty());
    }

    public String testRunner() {
        return metrics.testRunner();
    }

    public int testRuntimeClasspathEntries() {
        return metrics.testRuntimeClasspathEntries();
    }

    public int testLauncherClasspathEntries() {
        return metrics.testLauncherClasspathEntries();
    }

    public int testDiscoveryScanRoots() {
        return metrics.testDiscoveryScanRoots();
    }

    public long testRunnerStartupNanos() {
        return metrics.testRunnerStartupNanos();
    }

    public long testRunnerRequestNanos() {
        return metrics.testRunnerRequestNanos();
    }

    public int testWorkerStarts() {
        return metrics.testWorkerStarts();
    }

    public int testWorkerRequests() {
        return metrics.testWorkerRequests();
    }

    public static TestRunMetrics metrics(
            String testRunner,
            int testRuntimeClasspathEntries,
            int testLauncherClasspathEntries,
            int testDiscoveryScanRoots,
            long testRunnerStartupNanos,
            long testRunnerRequestNanos) {
        return new TestRunMetrics(
                testRunner,
                testRuntimeClasspathEntries,
                testLauncherClasspathEntries,
                testDiscoveryScanRoots,
                testRunnerStartupNanos,
                testRunnerRequestNanos);
    }

    public static TestRunMetrics metrics(
            String testRunner,
            int testRuntimeClasspathEntries,
            int testLauncherClasspathEntries,
            int testDiscoveryScanRoots,
            int testWorkerStarts,
            int testWorkerRequests,
            long testRunnerStartupNanos,
            long testRunnerRequestNanos) {
        return new TestRunMetrics(
                testRunner,
                testRuntimeClasspathEntries,
                testLauncherClasspathEntries,
                testDiscoveryScanRoots,
                testWorkerStarts,
                testWorkerRequests,
                testRunnerStartupNanos,
                testRunnerRequestNanos);
    }
}
