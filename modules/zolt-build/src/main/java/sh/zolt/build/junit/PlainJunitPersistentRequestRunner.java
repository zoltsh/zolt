package sh.zolt.build.junit;

import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestRunException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class PlainJunitPersistentRequestRunner {
    private static final String WORKER_ID = "worker-1";

    private PlainJunitPersistentRequestRunner() {
    }

    static PlainJunitWorkerPoolRunResult run(
            PlainJunitWorkerSlot slot,
            Path projectDirectory,
            List<Path> testRuntimeClasspath,
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            List<String> events,
            Optional<Path> profileDirectory) {
        int startsBefore = slot.processStarts();
        long startupBefore = slot.startupNanos();
        long requestStarted = System.nanoTime();
        PlainJunitWorkerRunResult result = slot.run(
                projectDirectory,
                testRuntimeClasspath,
                testOutputDirectory,
                testSelection,
                PlainJunitWorkerEvidence.reports(
                        reportsDirectory,
                        WORKER_ID),
                events,
                PlainJunitWorkerEvidence.profile(
                        profileDirectory,
                        WORKER_ID));
        if (result.workerResult().exitCode() != 0) {
            throw new TestRunException(
                    "JUnit worker tests failed with exit code "
                            + result.workerResult().exitCode()
                            + ". Fix failing tests, then run `zolt test` again.\n"
                            + result.workerResult().output().stripTrailing());
        }
        return new PlainJunitWorkerPoolRunResult(
                result.workerResult().output(),
                1,
                slot.processStarts() - startsBefore,
                slot.startupNanos() - startupBefore,
                Math.max(0L, System.nanoTime() - requestStarted));
    }

    static String workerId() {
        return WORKER_ID;
    }
}
