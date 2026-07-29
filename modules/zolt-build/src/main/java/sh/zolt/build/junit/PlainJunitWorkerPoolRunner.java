package sh.zolt.build.junit;

import sh.zolt.build.profile.TestProfileMerger;
import sh.zolt.project.ProjectConfig;
import sh.zolt.test.TestInventoryEntry;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.test.shard.TestWorkerPoolPlan;
import sh.zolt.test.shard.TestWorkerPoolWave;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class PlainJunitWorkerPoolRunner {
    private final PlainJunitWorkerSessionFactory sessionFactory;

    public PlainJunitWorkerPoolRunner(
            PlainJunitWorkerRunner plainJunitWorkerRunner) {
        this(PlainJunitWorkerSessionFactory.legacy(
                plainJunitWorkerRunner));
    }

    public PlainJunitWorkerPoolRunner(
            PlainJunitWorkerSessionFactory sessionFactory) {
        if (sessionFactory == null) {
            throw new IllegalArgumentException(
                    "Plain JUnit worker session factory is required.");
        }
        this.sessionFactory = sessionFactory;
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
        List<String> workerIds = workerIds(workerPoolPlan);
        PlainJunitWorkerEvidence.writeManifests(
                reportsDirectory,
                jvmArguments,
                workerIds);
        List<PlainJunitWorkerSlot> slots = workerIds.stream()
                .map(workerId -> workerSlot(
                        javaExecutable,
                        workerClasspath,
                        projectDirectory,
                        config,
                        testRuntimeClasspath,
                        jvmArguments,
                        environment,
                        reportsDirectory,
                        events,
                        profileDirectory,
                        workerId))
                .toList();
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
                    TestInventoryEntry entry = wave.entries().get(index);
                    PlainJunitWorkerSlot slot = slots.get(index);
                    futures.add(executor.submit(() -> new WorkerTaskResult(
                            entry.className(),
                            slot.run(
                                    testOutputDirectory,
                                    workerSelection(testSelection, entry)))));
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
            abortSlots(slots, failure);
            throw failure;
        } finally {
            executor.shutdownNow();
        }
        closeSlots(slots, null);
        return new PlainJunitWorkerPoolRunResult(
                output.toString(),
                workerRequests,
                slots.stream()
                        .mapToInt(PlainJunitWorkerSlot::processStarts)
                        .sum(),
                slots.stream()
                        .mapToLong(PlainJunitWorkerSlot::startupNanos)
                        .sum(),
                Math.max(0L, System.nanoTime() - requestStarted));
    }

    private PlainJunitWorkerSlot workerSlot(
            Path javaExecutable,
            List<Path> workerClasspath,
            Path projectDirectory,
            ProjectConfig config,
            List<Path> testRuntimeClasspath,
            TestJvmArguments jvmArguments,
            Map<String, String> environment,
            Optional<Path> reportsDirectory,
            List<String> events,
            Optional<Path> profileDirectory,
            String workerId) {
        return new PlainJunitWorkerSlot(
                sessionFactory,
                javaExecutable,
                workerClasspath,
                projectDirectory,
                testRuntimeClasspath,
                PlainJunitWorkerEvidence.jvmArguments(
                        jvmArguments,
                        workerId),
                PlainJunitWorkerEvidence.environment(
                        projectDirectory,
                        config,
                        environment,
                        jvmArguments,
                        workerId),
                PlainJunitWorkerEvidence.reports(
                        reportsDirectory,
                        workerId),
                events,
                PlainJunitWorkerEvidence.profile(
                        profileDirectory,
                        workerId));
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

    private static TestSelection workerSelection(
            TestSelection selection,
            TestInventoryEntry entry) {
        List<TestSelection.MethodSelector> methodSelectors =
                selection.methodSelectors().stream()
                        .filter(method -> method.className()
                                .equals(entry.className()))
                        .toList();
        return TestSelection.fromFields(
                methodSelectors.isEmpty()
                        ? List.of(entry.className())
                        : List.of(),
                methodSelectors,
                List.of(),
                selection.includedTags(),
                selection.excludedTags());
    }

    private static List<String> workerIds(
            TestWorkerPoolPlan workerPoolPlan) {
        int workers = workerPoolPlan.waves().stream()
                .mapToInt(wave -> wave.entries().size())
                .max()
                .orElse(0);
        return java.util.stream.IntStream.range(0, workers)
                .mapToObj(index -> "worker-" + (index + 1))
                .toList();
    }

    private static void closeSlots(
            List<PlainJunitWorkerSlot> slots,
            RuntimeException failure) {
        RuntimeException firstCloseFailure = null;
        for (PlainJunitWorkerSlot slot : slots) {
            try {
                slot.close();
            } catch (RuntimeException closeFailure) {
                if (failure != null) {
                    failure.addSuppressed(closeFailure);
                } else if (firstCloseFailure == null) {
                    firstCloseFailure = closeFailure;
                } else {
                    firstCloseFailure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure == null && firstCloseFailure != null) {
            throw firstCloseFailure;
        }
    }

    private static void abortSlots(
            List<PlainJunitWorkerSlot> slots,
            RuntimeException failure) {
        for (PlainJunitWorkerSlot slot : slots) {
            try {
                slot.abort();
            } catch (RuntimeException abortFailure) {
                failure.addSuppressed(abortFailure);
            }
        }
    }

    private record WorkerTaskResult(
            String className,
            PlainJunitWorkerRunResult result) {
    }
}
