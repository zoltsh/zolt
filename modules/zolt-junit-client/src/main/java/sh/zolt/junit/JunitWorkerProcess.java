package sh.zolt.junit;

import sh.zolt.test.TestSelection;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class JunitWorkerProcess implements AutoCloseable {
    private final JunitWorkerClient client;
    private final ProcessCloser processCloser;
    private final List<Path> testRuntimeClasspath;
    private boolean closed;

    JunitWorkerProcess(
            JunitWorkerClient client,
            ProcessCloser processCloser) {
        this(client, processCloser, List.of());
    }

    JunitWorkerProcess(
            JunitWorkerClient client,
            ProcessCloser processCloser,
            List<Path> testRuntimeClasspath) {
        if (client == null) {
            throw new IllegalArgumentException("JUnit worker client is required.");
        }
        if (processCloser == null) {
            throw new IllegalArgumentException("JUnit worker process closer is required.");
        }
        this.client = client;
        this.processCloser = processCloser;
        this.testRuntimeClasspath = testRuntimeClasspath == null
                ? List.of()
                : List.copyOf(testRuntimeClasspath);
    }

    public JunitWorkerClient.WorkerRunResult run(Path testOutputDirectory) {
        return run(testOutputDirectory, TestSelection.empty());
    }

    public JunitWorkerClient.WorkerRunResult run(Path testOutputDirectory, TestSelection testSelection) {
        return run(testOutputDirectory, testSelection, Optional.empty(), List.of());
    }

    public JunitWorkerClient.WorkerRunResult run(
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            List<String> events) {
        return run(testOutputDirectory, testSelection, reportsDirectory, events, Optional.empty());
    }

    public JunitWorkerClient.WorkerRunResult run(
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            List<String> events,
            Optional<Path> profileDirectory) {
        if (closed) {
            throw new JunitWorkerClientException("JUnit worker process is already closed.");
        }
        return client.run(
                testRuntimeClasspath,
                testOutputDirectory,
                testSelection,
                reportsDirectory,
                events,
                profileDirectory);
    }

    public JunitWorkerClient.WorkerRunResult run(
            Path projectDirectory,
            List<Path> requestTestRuntimeClasspath,
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            List<String> events,
            Optional<Path> profileDirectory) {
        if (closed) {
            throw new JunitWorkerClientException("JUnit worker process is already closed.");
        }
        return client.run(
                projectDirectory,
                requestTestRuntimeClasspath,
                testOutputDirectory,
                testSelection,
                reportsDirectory,
                events,
                profileDirectory);
    }

    public void abort() {
        if (closed) {
            return;
        }
        closed = true;
        processCloser.abort();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            client.close();
        } finally {
            processCloser.close();
        }
    }

    @FunctionalInterface
    interface ProcessCloser {
        void close();

        default void abort() {
            close();
        }
    }
}
