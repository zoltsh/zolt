package sh.zolt.build.junit;

public record PlainJunitWorkerPoolRunResult(
        String output,
        int workerRequests,
        int workerStarts,
        long startupNanos,
        long requestNanos) {
    public PlainJunitWorkerPoolRunResult(
            String output,
            int workerRequests,
            long startupNanos,
            long requestNanos) {
        this(
                output,
                workerRequests,
                workerRequests,
                startupNanos,
                requestNanos);
    }
}
