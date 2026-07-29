package sh.zolt.build.junit;

import sh.zolt.build.profile.TestProfileMerger;
import sh.zolt.project.ProjectConfig;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.test.shard.TestWorkerPoolPlan;
import sh.zolt.test.shard.TestWorkerPoolWave;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class PlainJunitWorkerPoolRunner implements AutoCloseable {
    private final PlainJunitWorkerSessionFactory sessionFactory;
    private final boolean persistent;
    private final Map<WorkerKey, PlainJunitWorkerSlot> persistentSlots =
            new LinkedHashMap<>();

    public PlainJunitWorkerPoolRunner(
            PlainJunitWorkerRunner plainJunitWorkerRunner) {
        this(PlainJunitWorkerSessionFactory.legacy(
                plainJunitWorkerRunner));
    }

    public PlainJunitWorkerPoolRunner(
            PlainJunitWorkerSessionFactory sessionFactory) {
        this(sessionFactory, false);
    }

    private PlainJunitWorkerPoolRunner(
            PlainJunitWorkerSessionFactory sessionFactory,
            boolean persistent) {
        if (sessionFactory == null) {
            throw new IllegalArgumentException(
                    "Plain JUnit worker session factory is required.");
        }
        this.sessionFactory = sessionFactory;
        this.persistent = persistent;
    }

    public static PlainJunitWorkerPoolRunner persistent(
            PlainJunitWorkerSessionFactory sessionFactory) {
        return new PlainJunitWorkerPoolRunner(sessionFactory, true);
    }

    public boolean reusesProcesses() {
        return persistent;
    }

    public PlainJunitWorkerPoolRunResult run(
            Path javaExecutable,
            List<Path> workerClasspath,
            Path projectDirectory,
            ProjectConfig config,
            List<Path> testRuntimeClasspath,
            Path testOutputDirectory,
            TestSelection testSelection,
            TestWorkerPoolPlan workerPoolPlan,
            TestJvmArguments jvmArguments,
            Map<String, String> environment,
            Optional<Path> reportsDirectory,
            List<String> events,
            Optional<Path> profileDirectory) {
        if (persistent && workerPoolPlan.empty()) {
            return runPersistentRequest(
                    javaExecutable,
                    workerClasspath,
                    projectDirectory,
                    config,
                    testRuntimeClasspath,
                    testOutputDirectory,
                    testSelection,
                    jvmArguments,
                    environment,
                    reportsDirectory,
                    events,
                    profileDirectory);
        }
        List<String> workerIds =
                PlainJunitWorkerPoolSupport.workerIds(workerPoolPlan);
        PlainJunitWorkerEvidence.writeManifests(
                reportsDirectory,
                jvmArguments,
                workerIds);
        List<PlainJunitWorkerSlot> slots = workerIds.stream()
                .map(workerId -> slot(
                        javaExecutable,
                        workerClasspath,
                        projectDirectory,
                        config,
                        jvmArguments,
                        environment,
                        workerId))
                .toList();
        int startsBefore = slots.stream()
                .mapToInt(PlainJunitWorkerSlot::processStarts)
                .sum();
        long startupBefore = slots.stream()
                .mapToLong(PlainJunitWorkerSlot::startupNanos)
                .sum();
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.max(1, slots.size()));
        StringBuilder output = new StringBuilder();
        long requestStarted = System.nanoTime();
        int workerRequests = 0;
        try {
            for (TestWorkerPoolWave wave : workerPoolPlan.waves()) {
                List<Future<WorkerTaskResult>> futures =
                        new ArrayList<>();
                for (int index = 0;
                        index < wave.entries().size();
                        index++) {
                    sh.zolt.test.TestInventoryEntry entry =
                            wave.entries().get(index);
                    PlainJunitWorkerSlot slot = slots.get(index);
                    String workerId = workerIds.get(index);
                    futures.add(executor.submit(() -> new WorkerTaskResult(
                            entry.className(),
                            slot.run(
                                    projectDirectory,
                                    testRuntimeClasspath,
                                    testOutputDirectory,
                                    PlainJunitWorkerPoolSupport.workerSelection(
                                            testSelection,
                                            entry),
                                    PlainJunitWorkerEvidence.reports(
                                            reportsDirectory,
                                            workerId),
                                    events,
                                    PlainJunitWorkerEvidence.profile(
                                            profileDirectory,
                                            workerId)))));
                }
                for (Future<WorkerTaskResult> future : futures) {
                    WorkerTaskResult task = getWorkerTask(future);
                    workerRequests++;
                    output.append(task.result().workerResult().output());
                    throwForFailedTest(task);
                }
            }
            profileDirectory.ifPresent(directory ->
                    TestProfileMerger.mergeWorkerProfiles(
                            directory,
                            workerIds));
        } catch (RuntimeException failure) {
            executor.shutdownNow();
            PlainJunitWorkerPoolSupport.abortSlots(slots, failure);
            if (persistent) {
                persistentSlots.clear();
            }
            throw failure;
        } finally {
            executor.shutdownNow();
        }
        if (!persistent) {
            PlainJunitWorkerPoolSupport.closeSlots(slots, null);
        }
        return new PlainJunitWorkerPoolRunResult(
                output.toString(),
                workerRequests,
                slots.stream()
                        .mapToInt(PlainJunitWorkerSlot::processStarts)
                        .sum()
                        - startsBefore,
                slots.stream()
                        .mapToLong(PlainJunitWorkerSlot::startupNanos)
                        .sum()
                        - startupBefore,
                Math.max(0L, System.nanoTime() - requestStarted));
    }

    private PlainJunitWorkerPoolRunResult runPersistentRequest(
            Path javaExecutable,
            List<Path> workerClasspath,
            Path projectDirectory,
            ProjectConfig config,
            List<Path> testRuntimeClasspath,
            Path testOutputDirectory,
            TestSelection testSelection,
            TestJvmArguments jvmArguments,
            Map<String, String> environment,
            Optional<Path> reportsDirectory,
            List<String> events,
            Optional<Path> profileDirectory) {
        String workerId = PlainJunitPersistentRequestRunner.workerId();
        PlainJunitWorkerEvidence.writeManifests(
                reportsDirectory,
                jvmArguments,
                List.of(workerId));
        PlainJunitWorkerSlot slot = slot(
                javaExecutable,
                workerClasspath,
                projectDirectory,
                config,
                jvmArguments,
                environment,
                workerId);
        try {
            return PlainJunitPersistentRequestRunner.run(
                    slot,
                    projectDirectory,
                    testRuntimeClasspath,
                    testOutputDirectory,
                    testSelection,
                    reportsDirectory,
                    events,
                    profileDirectory);
        } catch (RuntimeException failure) {
            slot.abort();
            persistentSlots.clear();
            throw failure;
        }
    }

    private PlainJunitWorkerSlot slot(
            Path javaExecutable,
            List<Path> workerClasspath,
            Path projectDirectory,
            ProjectConfig config,
            TestJvmArguments jvmArguments,
            Map<String, String> environment,
            String workerId) {
        TestJvmArguments workerJvmArguments =
                PlainJunitWorkerEvidence.jvmArguments(
                        jvmArguments,
                        workerId);
        Map<String, String> workerEnvironment =
                PlainJunitWorkerEvidence.environment(
                        projectDirectory,
                        config,
                        environment,
                        jvmArguments,
                        workerId);
        if (!persistent) {
            return new PlainJunitWorkerSlot(
                    sessionFactory,
                    javaExecutable,
                    workerClasspath,
                    workerJvmArguments,
                    workerEnvironment);
        }
        WorkerKey key = new WorkerKey(
                javaExecutable.toAbsolutePath().normalize(),
                // A JVM's default filesystem keeps its launch directory even if user.dir changes.
                // Isolate member directories so project-relative test I/O preserves single-project semantics.
                projectDirectory.toAbsolutePath().normalize(),
                List.copyOf(workerClasspath),
                workerJvmArguments,
                reusableEnvironment(workerEnvironment),
                workerId);
        return persistentSlots.computeIfAbsent(
                key,
                ignored -> new PlainJunitWorkerSlot(
                        sessionFactory,
                        javaExecutable,
                        workerClasspath,
                        workerJvmArguments,
                        workerEnvironment));
    }

    private static Map<String, String> reusableEnvironment(
            Map<String, String> environment) {
        Map<String, String> reusable = new LinkedHashMap<>(environment);
        reusable.remove("ZOLT_TEST_WORKER_OUTPUT_DIR");
        return Map.copyOf(reusable);
    }

    private static WorkerTaskResult getWorkerTask(
            Future<WorkerTaskResult> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TestRunException(
                    "JUnit worker pool was interrupted while waiting "
                            + "for test results.",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof TestRunException testRunException) {
                throw testRunException;
            }
            throw new TestRunException(
                    "JUnit worker pool failed while running tests.",
                    cause);
        }
    }

    private static void throwForFailedTest(WorkerTaskResult task) {
        if (task.result().workerResult().exitCode() == 0) {
            return;
        }
        throw new TestRunException(
                "JUnit worker tests failed with exit code "
                        + task.result().workerResult().exitCode()
                        + " in "
                        + task.className()
                        + ". Fix failing tests, then run `zolt test` again.\n"
                        + task.result().workerResult().output()
                                .stripTrailing());
    }

    @Override
    public void close() {
        List<PlainJunitWorkerSlot> slots =
                List.copyOf(persistentSlots.values());
        persistentSlots.clear();
        PlainJunitWorkerPoolSupport.closeSlots(slots, null);
    }

    private record WorkerKey(
            Path javaExecutable,
            Path projectDirectory,
            List<Path> workerClasspath,
            TestJvmArguments jvmArguments,
            Map<String, String> environment,
            String workerId) {
    }

    private record WorkerTaskResult(
            String className,
            PlainJunitWorkerRunResult result) {
    }
}
