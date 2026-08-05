package sh.zolt.workspace.testpool;

/**
 * What the member test pool did for one run.
 *
 * @param workers how many members were allowed to run at once
 * @param queueNanos aggregate time members spent waiting for a slot
 * @param startupNanos aggregate time members spent booting their test JVMs
 * @param requestNanos aggregate time members spent inside test requests, boot excluded
 */
public record WorkspaceTestPoolMetrics(
        int workers,
        long queueNanos,
        long startupNanos,
        long requestNanos) {
    private static final WorkspaceTestPoolMetrics EMPTY =
            new WorkspaceTestPoolMetrics(0, 0L, 0L, 0L);

    public WorkspaceTestPoolMetrics {
        workers = Math.max(0, workers);
        queueNanos = Math.max(0L, queueNanos);
        startupNanos = Math.max(0L, startupNanos);
        requestNanos = Math.max(0L, requestNanos);
    }

    public static WorkspaceTestPoolMetrics empty() {
        return EMPTY;
    }
}
