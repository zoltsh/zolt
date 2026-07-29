package sh.zolt.build.testruntime.execution;

import sh.zolt.build.junit.PlainJunitWorkerPoolRunner;
import sh.zolt.build.junit.PlainJunitWorkerRunner;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

public record PlainJunitRunners(
        Supplier<List<Path>> workerClasspath,
        PlainJunitWorkerRunner workerRunner,
        PlainJunitWorkerPoolRunner workerPoolRunner) {
    public PlainJunitRunners {
        if (workerClasspath == null
                || workerRunner == null
                || workerPoolRunner == null) {
            throw new IllegalArgumentException(
                    "Plain JUnit runners and worker classpath are "
                            + "required.");
        }
    }

    public static PlainJunitRunners legacy(
            Supplier<List<Path>> workerClasspath,
            PlainJunitWorkerRunner workerRunner) {
        return new PlainJunitRunners(
                workerClasspath,
                workerRunner,
                new PlainJunitWorkerPoolRunner(workerRunner));
    }
}
