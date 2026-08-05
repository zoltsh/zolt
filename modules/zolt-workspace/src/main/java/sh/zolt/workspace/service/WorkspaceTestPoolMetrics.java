package sh.zolt.workspace.service;

/**
 * What the member test pool actually did for one run.
 *
 * @param workers how many members were allowed to run at once
 * @param queueNanos aggregate time members spent waiting for a slot
 */
public record WorkspaceTestPoolMetrics(int workers, long queueNanos) {
    private static final WorkspaceTestPoolMetrics EMPTY = new WorkspaceTestPoolMetrics(0, 0L);

    public WorkspaceTestPoolMetrics {
        workers = Math.max(0, workers);
        queueNanos = Math.max(0L, queueNanos);
    }

    public static WorkspaceTestPoolMetrics empty() {
        return EMPTY;
    }
}
