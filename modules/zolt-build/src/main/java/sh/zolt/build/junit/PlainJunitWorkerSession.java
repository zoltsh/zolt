package sh.zolt.build.junit;

import sh.zolt.test.TestSelection;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface PlainJunitWorkerSession extends AutoCloseable {
    PlainJunitWorkerRunResult run(
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            List<String> events,
            Optional<Path> profileDirectory);

    default PlainJunitWorkerRunResult run(
            Path projectDirectory,
            List<Path> testRuntimeClasspath,
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            List<String> events,
            Optional<Path> profileDirectory) {
        return run(
                testOutputDirectory,
                testSelection,
                reportsDirectory,
                events,
                profileDirectory);
    }

    long startupNanos();

    int processStarts();

    default void abort() {
        close();
    }

    @Override
    void close();
}
