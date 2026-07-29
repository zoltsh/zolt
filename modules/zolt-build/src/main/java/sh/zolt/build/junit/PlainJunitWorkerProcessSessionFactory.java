package sh.zolt.build.junit;

import sh.zolt.junit.JunitWorkerClient;
import sh.zolt.junit.JunitWorkerClientException;
import sh.zolt.junit.JunitWorkerProcess;
import sh.zolt.junit.JunitWorkerProcessLauncher;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.runtime.TestRunException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PlainJunitWorkerProcessSessionFactory
        implements PlainJunitWorkerSessionFactory {
    @Override
    public PlainJunitWorkerSession open(
            Path javaExecutable,
            List<Path> workerClasspath,
            Path projectDirectory,
            List<Path> testRuntimeClasspath,
            TestJvmArguments jvmArguments,
            Map<String, String> environment) {
        long started = System.nanoTime();
        try {
            JunitWorkerProcess process =
                    new JunitWorkerProcessLauncher(
                            javaExecutable,
                            workerClasspath)
                            .start(
                                    projectDirectory,
                                    testRuntimeClasspath,
                                    jvmArguments.values(),
                                    environment);
            return new ProcessSession(
                    process,
                    Math.max(0L, System.nanoTime() - started));
        } catch (JunitWorkerClientException exception) {
            throw new TestRunException(exception.getMessage(), exception);
        }
    }

    private static final class ProcessSession
            implements PlainJunitWorkerSession {
        private final JunitWorkerProcess process;
        private final long startupNanos;

        private ProcessSession(
                JunitWorkerProcess process,
                long startupNanos) {
            this.process = process;
            this.startupNanos = startupNanos;
        }

        @Override
        public PlainJunitWorkerRunResult run(
                Path testOutputDirectory,
                TestSelection testSelection,
                Optional<Path> reportsDirectory,
                List<String> events,
                Optional<Path> profileDirectory) {
            long started = System.nanoTime();
            try {
                JunitWorkerClient.WorkerRunResult result = process.run(
                        testOutputDirectory,
                        testSelection,
                        reportsDirectory,
                        events,
                        profileDirectory);
                return new PlainJunitWorkerRunResult(
                        result,
                        0L,
                        Math.max(0L, System.nanoTime() - started));
            } catch (JunitWorkerClientException exception) {
                throw new TestRunException(exception.getMessage(), exception);
            }
        }

        @Override
        public long startupNanos() {
            return startupNanos;
        }

        @Override
        public int processStarts() {
            return 1;
        }

        @Override
        public void abort() {
            process.abort();
        }

        @Override
        public void close() {
            try {
                process.close();
            } catch (JunitWorkerClientException exception) {
                throw new TestRunException(exception.getMessage(), exception);
            }
        }
    }
}
