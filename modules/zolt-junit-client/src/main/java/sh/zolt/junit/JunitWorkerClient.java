package sh.zolt.junit;

import sh.zolt.test.TestSelection;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class JunitWorkerClient implements AutoCloseable {
    private final BufferedReader output;
    private final BufferedWriter input;
    private int requestCounter;
    private boolean closed;

    public JunitWorkerClient(Reader output, Writer input) {
        if (output == null) {
            throw new IllegalArgumentException("JUnit worker output reader is required.");
        }
        if (input == null) {
            throw new IllegalArgumentException("JUnit worker input writer is required.");
        }
        this.output = new BufferedReader(output);
        this.input = new BufferedWriter(input);
    }

    public WorkerRunResult run(Path testOutputDirectory) {
        return run(testOutputDirectory, TestSelection.empty());
    }

    public WorkerRunResult run(Path testOutputDirectory, TestSelection testSelection) {
        return run(testOutputDirectory, testSelection, Optional.empty(), List.of());
    }

    public WorkerRunResult run(
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            List<String> events) {
        return run(testOutputDirectory, testSelection, reportsDirectory, events, Optional.empty());
    }

    public WorkerRunResult run(
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            List<String> events,
            Optional<Path> profileDirectory) {
        return run(
                List.of(),
                testOutputDirectory,
                testSelection,
                reportsDirectory,
                events,
                profileDirectory);
    }

    public WorkerRunResult run(
            List<Path> testRuntimeClasspath,
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            List<String> events,
            Optional<Path> profileDirectory) {
        return run(
                null,
                testRuntimeClasspath,
                testOutputDirectory,
                testSelection,
                reportsDirectory,
                events,
                profileDirectory);
    }

    public WorkerRunResult run(
            Path projectDirectory,
            List<Path> testRuntimeClasspath,
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            List<String> events,
            Optional<Path> profileDirectory) {
        ensureOpen();
        String requestId = nextRequestId();
        writeFrame(JunitWorkerProtocol.runRequest(
                requestId,
                projectDirectory,
                testRuntimeClasspath,
                testOutputDirectory,
                testSelection,
                reportsDirectory,
                events,
                profileDirectory));
        return readRunResult(requestId);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        String requestId = nextRequestId();
        writeFrame(JunitWorkerProtocol.quitRequest(requestId));
        JunitWorkerProtocol.WorkerResult result = readResult(requestId, new StringBuilder());
        if (result.exitCode() != 0) {
            throw new JunitWorkerClientException(
                    "JUnit worker rejected quit request with exit code " + result.exitCode() + ".");
        }
    }

    private WorkerRunResult readRunResult(String requestId) {
        StringBuilder workerOutput = new StringBuilder();
        JunitWorkerProtocol.WorkerResult result = readResult(requestId, workerOutput);
        return new WorkerRunResult(workerOutput.toString(), result.exitCode());
    }

    private JunitWorkerProtocol.WorkerResult readResult(String requestId, StringBuilder workerOutput) {
        try {
            String line;
            while ((line = output.readLine()) != null) {
                if (line.startsWith(JunitWorkerProtocol.RESULT_PREFIX + "\t")) {
                    JunitWorkerProtocol.WorkerResult result = JunitWorkerProtocol.parseResult(line);
                    if (!requestId.equals(result.requestId())) {
                        throw new JunitWorkerClientException(
                                "JUnit worker returned result for request `"
                                        + result.requestId()
                                        + "` while waiting for `"
                                        + requestId
                                        + "`.");
                    }
                    return result;
                }
                workerOutput.append(line).append('\n');
            }
        } catch (IOException exception) {
            throw new JunitWorkerClientException(
                    "Could not read JUnit worker output. Restart the test command and try again.",
                    exception);
        } catch (IllegalArgumentException exception) {
            throw new JunitWorkerClientException(exception.getMessage(), exception);
        }
        String output = workerOutput.toString().stripTrailing();
        if (output.isBlank()) {
            throw new JunitWorkerClientException(
                    "JUnit worker exited before sending a result for request `" + requestId + "`.");
        }
        throw new JunitWorkerClientException(
                "JUnit worker exited before sending a result for request `" + requestId + "`.\n" + output);
    }

    private void writeFrame(String frame) {
        try {
            input.write(frame);
            input.write('\n');
            input.flush();
        } catch (IOException exception) {
            throw new JunitWorkerClientException(
                    "Could not write JUnit worker request. Restart the test command and try again.",
                    exception);
        }
    }

    private String nextRequestId() {
        requestCounter++;
        return "junit-" + requestCounter;
    }

    private void ensureOpen() {
        if (closed) {
            throw new JunitWorkerClientException("JUnit worker client is already closed.");
        }
    }

    public record WorkerRunResult(String output, int exitCode) {
    }
}
