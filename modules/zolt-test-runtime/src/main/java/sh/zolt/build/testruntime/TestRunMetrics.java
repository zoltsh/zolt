package sh.zolt.build.testruntime;

public record TestRunMetrics(
        String testRunner,
        int testRuntimeClasspathEntries,
        int testLauncherClasspathEntries,
        int testDiscoveryScanRoots,
        int testWorkerStarts,
        int testWorkerRequests,
        long testRunnerStartupNanos,
        long testRunnerRequestNanos) {
    public TestRunMetrics {
        testRunner = testRunner == null || testRunner.isBlank() ? "unknown" : testRunner;
    }

    public TestRunMetrics(
            String testRunner,
            int testRuntimeClasspathEntries,
            int testLauncherClasspathEntries,
            int testDiscoveryScanRoots,
            long testRunnerStartupNanos,
            long testRunnerRequestNanos) {
        this(
                testRunner,
                testRuntimeClasspathEntries,
                testLauncherClasspathEntries,
                testDiscoveryScanRoots,
                0,
                0,
                testRunnerStartupNanos,
                testRunnerRequestNanos);
    }

    public static TestRunMetrics unknown() {
        return new TestRunMetrics(
                "unknown",
                0,
                0,
                0,
                0,
                0,
                -1L,
                -1L);
    }
}
