package sh.zolt.workspace.service;

import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.workspace.state.WorkspaceFileSnapshot;

/**
 * The command's running totals, kept apart from the caches and indices they describe.
 *
 * <p>Every method is synchronized because members execute in parallel and each one can record a
 * classpath calculation or a cache hit. The reads that assemble {@link
 * WorkspaceExecutionContext.Metrics} take the same lock, so a published set of counters is one
 * consistent snapshot rather than a torn read across twenty fields.
 */
final class WorkspaceExecutionCounters {
    private long classpathCalculationNanos;
    private long packageCalculationNanos;
    private long memberExecutionNanos;
    private long schedulerIdleNanos;
    private long fileSnapshotNanos;
    private long bytesHashed;
    private int classpathCacheHits;
    private int packageCacheHits;
    private int readyQueuePeak;
    private int filesHashed;
    private int filesStatted;
    private int filesReused;
    private int membersConsidered;
    private int membersDeclaredClean;
    private int memberPipelineInvocations;
    private int membersAdmitted;
    private int membersFinalized;
    private int runtimeClasspathCalculations;
    private int testClasspathCalculations;

    synchronized void addClasspathCalculation(
            long durationNanos,
            WorkspaceBuildRequirements requirements) {
        classpathCalculationNanos += durationNanos;
        if (requirements.mainRuntimeClasspath()) {
            runtimeClasspathCalculations++;
        }
        if (requirements.testCompileClasspath()) {
            testClasspathCalculations++;
        }
    }

    synchronized void addPackageCalculation(long durationNanos) {
        packageCalculationNanos += durationNanos;
    }

    synchronized void addClasspathCacheHit() {
        classpathCacheHits++;
    }

    synchronized void addPackageCacheHit() {
        packageCacheHits++;
    }

    synchronized void addMemberExecutionNanos(long durationNanos) {
        memberExecutionNanos += Math.max(0L, durationNanos);
    }

    synchronized void addSchedulerMetrics(long idleNanos, int queuePeak) {
        schedulerIdleNanos += Math.max(0L, idleNanos);
        readyQueuePeak = Math.max(readyQueuePeak, queuePeak);
    }

    /**
     * The snapshot's running totals, not deltas: {@code statted} counts every input the command
     * considered, {@code hashed} only the ones whose bytes it had to read, and {@code reused} the
     * ones a recorded hash answered for. On a warm command the second is zero.
     */
    synchronized void addFileSnapshotMetrics(long durationNanos, WorkspaceFileSnapshot snapshot) {
        fileSnapshotNanos += Math.max(0L, durationNanos);
        bytesHashed = Math.max(bytesHashed, snapshot.bytesHashed());
        filesHashed = Math.max(filesHashed, snapshot.filesHashed());
        filesStatted = Math.max(filesStatted, snapshot.filesStatted());
        filesReused = Math.max(filesReused, snapshot.filesReused());
    }

    /**
     * {@code admitted} is how many members the scheduler let into the executor at all — the number
     * that could cost a classpath. {@code pipelineInvocations} is the subset that ran the canonical
     * member build; {@code finalized} is the subset that only had its clean outputs assured.
     */
    synchronized void addDirtyPlanMetrics(
            int considered,
            int admitted,
            int pipelineInvocations,
            int finalized) {
        membersConsidered += Math.max(0, considered);
        membersAdmitted += Math.max(0, admitted);
        memberPipelineInvocations += Math.max(0, pipelineInvocations);
        membersFinalized += Math.max(0, finalized);
        membersDeclaredClean += Math.max(0, considered - pipelineInvocations);
    }

    synchronized WorkspaceExecutionContext.Metrics metrics(
            long graphConstructionNanos,
            int classpathCalculations,
            int packageCalculations,
            WorkspaceAbiIndex abiIndex,
            WorkspaceToolchainIndex toolchainIndex,
            VerifiedArtifactIndex artifactIndex) {
        return new WorkspaceExecutionContext.Metrics(
                graphConstructionNanos,
                classpathCalculationNanos,
                packageCalculationNanos,
                memberExecutionNanos,
                schedulerIdleNanos,
                classpathCalculations,
                packageCalculations,
                classpathCacheHits,
                packageCacheHits,
                readyQueuePeak,
                fileSnapshotNanos,
                bytesHashed,
                filesHashed,
                filesStatted,
                filesReused,
                membersConsidered,
                membersDeclaredClean,
                memberPipelineInvocations,
                membersAdmitted,
                membersFinalized,
                runtimeClasspathCalculations,
                testClasspathCalculations,
                abiIndex.reads(),
                abiIndex.hits(),
                toolchainIndex.resolutions(),
                toolchainIndex.hits(),
                toolchainIndex.lockfileParses(),
                toolchainIndex.identityCalculations(),
                toolchainIndex.identityHits(),
                artifactIndex.metrics());
    }
}
