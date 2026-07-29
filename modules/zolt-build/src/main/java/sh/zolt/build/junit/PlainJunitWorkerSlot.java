package sh.zolt.build.junit;

import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.runtime.TestRunException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class PlainJunitWorkerSlot implements AutoCloseable {
    private static final int MAX_REQUESTS_PER_PROCESS = 64;

    private final PlainJunitWorkerSessionFactory sessionFactory;
    private final Path javaExecutable;
    private final List<Path> workerClasspath;
    private final Path projectDirectory;
    private final List<Path> testRuntimeClasspath;
    private final TestJvmArguments jvmArguments;
    private final Map<String, String> environment;
    private final Optional<Path> reportsDirectory;
    private final List<String> events;
    private final Optional<Path> profileDirectory;

    private PlainJunitWorkerSession session;
    private int observedSessionStarts;
    private int requestsSinceStart;
    private int requestSequence;
    private int processStarts;
    private long startupNanos;

    PlainJunitWorkerSlot(
            PlainJunitWorkerSessionFactory sessionFactory,
            Path javaExecutable,
            List<Path> workerClasspath,
            Path projectDirectory,
            List<Path> testRuntimeClasspath,
            TestJvmArguments jvmArguments,
            Map<String, String> environment,
            Optional<Path> reportsDirectory,
            List<String> events,
            Optional<Path> profileDirectory) {
        this.sessionFactory = sessionFactory;
        this.javaExecutable = javaExecutable;
        this.workerClasspath = List.copyOf(workerClasspath);
        this.projectDirectory = projectDirectory;
        this.testRuntimeClasspath =
                List.copyOf(testRuntimeClasspath);
        this.jvmArguments = jvmArguments;
        this.environment = Map.copyOf(environment);
        this.reportsDirectory = reportsDirectory;
        this.events = List.copyOf(events);
        this.profileDirectory = profileDirectory;
    }

    PlainJunitWorkerRunResult run(
            Path testOutputDirectory,
            TestSelection testSelection) {
        Optional<Path> requestProfileDirectory =
                requestProfileDirectory(++requestSequence);
        TestRunException firstFailure;
        try {
            return runOnce(
                    testOutputDirectory,
                    testSelection,
                    requestProfileDirectory);
        } catch (TestRunException failure) {
            firstFailure = failure;
            discard(failure);
        }
        try {
            return runOnce(
                    testOutputDirectory,
                    testSelection,
                    requestProfileDirectory);
        } catch (TestRunException failure) {
            failure.addSuppressed(firstFailure);
            discard(failure);
            throw failure;
        }
    }

    int processStarts() {
        return processStarts;
    }

    long startupNanos() {
        return startupNanos;
    }

    private PlainJunitWorkerRunResult runOnce(
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> requestProfileDirectory) {
        PlainJunitWorkerSession active = session();
        PlainJunitWorkerRunResult result = active.run(
                testOutputDirectory,
                testSelection,
                reportsDirectory,
                events,
                requestProfileDirectory);
        observeStarts(active);
        startupNanos += Math.max(0L, result.startupNanos());
        requestsSinceStart++;
        if (requestsSinceStart >= MAX_REQUESTS_PER_PROCESS) {
            closeActive();
        }
        return result;
    }

    private Optional<Path> requestProfileDirectory(int request) {
        return profileDirectory.map(directory -> directory
                .resolve("requests")
                .resolve("request-%06d".formatted(request)));
    }

    private PlainJunitWorkerSession session() {
        if (session != null) {
            return session;
        }
        session = sessionFactory.open(
                javaExecutable,
                workerClasspath,
                projectDirectory,
                testRuntimeClasspath,
                jvmArguments,
                environment);
        requestsSinceStart = 0;
        observedSessionStarts = session.processStarts();
        processStarts += observedSessionStarts;
        startupNanos += Math.max(0L, session.startupNanos());
        return session;
    }

    private void observeStarts(PlainJunitWorkerSession active) {
        int starts = active.processStarts();
        if (starts > observedSessionStarts) {
            processStarts += starts - observedSessionStarts;
            observedSessionStarts = starts;
        }
    }

    private void discard(RuntimeException failure) {
        if (session == null) {
            return;
        }
        PlainJunitWorkerSession active = session;
        session = null;
        try {
            active.abort();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private void closeActive() {
        if (session == null) {
            return;
        }
        PlainJunitWorkerSession active = session;
        session = null;
        active.close();
    }

    @Override
    public void close() {
        closeActive();
    }

    void abort() {
        if (session == null) {
            return;
        }
        PlainJunitWorkerSession active = session;
        session = null;
        active.abort();
    }
}
