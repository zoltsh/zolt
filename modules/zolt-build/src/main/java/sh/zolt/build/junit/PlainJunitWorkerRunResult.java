package sh.zolt.build.junit;

import sh.zolt.junit.JunitWorkerClient;
import sh.zolt.test.TestSelection;

public record PlainJunitWorkerRunResult(
        JunitWorkerClient.WorkerRunResult workerResult,
        long startupNanos,
        long requestNanos) {
    public boolean successful(TestSelection selection) {
        if (workerResult.exitCode() == 0) {
            return true;
        }
        String output = workerResult.output();
        return workerResult.exitCode() == 2
                && selection.emptySelection()
                && (output.contains("Tests found: 0")
                        || output.contains("[         0 tests found"));
    }
}
