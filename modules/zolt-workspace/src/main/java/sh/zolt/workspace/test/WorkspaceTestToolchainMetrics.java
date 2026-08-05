package sh.zolt.workspace.test;

import sh.zolt.workspace.service.WorkspaceExecutionContext;

public record WorkspaceTestToolchainMetrics(
        int lockfileParses,
        int mainIdentityCalculations,
        int mainIdentityCacheHits,
        int testRuntimeIdentityCalculations,
        int testRuntimeIdentityCacheHits) {
    public WorkspaceTestToolchainMetrics {
        lockfileParses = Math.max(0, lockfileParses);
        mainIdentityCalculations = Math.max(0, mainIdentityCalculations);
        mainIdentityCacheHits = Math.max(0, mainIdentityCacheHits);
        testRuntimeIdentityCalculations =
                Math.max(0, testRuntimeIdentityCalculations);
        testRuntimeIdentityCacheHits =
                Math.max(0, testRuntimeIdentityCacheHits);
    }

    public static WorkspaceTestToolchainMetrics empty() {
        return new WorkspaceTestToolchainMetrics(0, 0, 0, 0, 0);
    }

    static WorkspaceTestToolchainMetrics combine(
            WorkspaceExecutionContext.Metrics main,
            WorkspaceTestToolchainMetrics test) {
        WorkspaceExecutionContext.Metrics mainMetrics = main == null
                ? new WorkspaceExecutionContext.Metrics(0L, 0L, 0L, 0L, 0, 0, 0, 0)
                : main;
        WorkspaceTestToolchainMetrics testMetrics =
                test == null ? empty() : test;
        return new WorkspaceTestToolchainMetrics(
                Math.max(
                        mainMetrics.toolchainLockfileParses(),
                        testMetrics.lockfileParses()),
                mainMetrics.toolchainIdentityCalculations(),
                mainMetrics.toolchainIdentityCacheHits(),
                testMetrics.testRuntimeIdentityCalculations(),
                testMetrics.testRuntimeIdentityCacheHits());
    }
}
