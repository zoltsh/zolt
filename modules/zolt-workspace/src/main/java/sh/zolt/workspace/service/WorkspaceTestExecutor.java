package sh.zolt.workspace.service;

import sh.zolt.cancel.BuildCancellation;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.workspace.testpool.WorkspaceTestConcurrency;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class WorkspaceTestExecutor {
    private static final long DEFAULT_SHUTDOWN_WAIT_MILLIS =
            TimeUnit.SECONDS.toMillis(30);

    private final WorkspaceTestConcurrency concurrency;
    private final long shutdownWaitMillis;

    WorkspaceTestExecutor() {
        this(WorkspaceTestConcurrency.adaptive(), DEFAULT_SHUTDOWN_WAIT_MILLIS);
    }

    WorkspaceTestExecutor(int maximumWorkers) {
        this(WorkspaceTestConcurrency.of(maximumWorkers), DEFAULT_SHUTDOWN_WAIT_MILLIS);
    }

    WorkspaceTestExecutor(int maximumWorkers, long shutdownWaitMillis) {
        this(WorkspaceTestConcurrency.of(maximumWorkers), shutdownWaitMillis);
    }

    WorkspaceTestExecutor(WorkspaceTestConcurrency concurrency) {
        this(concurrency, DEFAULT_SHUTDOWN_WAIT_MILLIS);
    }

    WorkspaceTestExecutor(
            WorkspaceTestConcurrency concurrency,
            long shutdownWaitMillis) {
        this.concurrency = concurrency == null
                ? WorkspaceTestConcurrency.adaptive()
                : concurrency;
        this.shutdownWaitMillis = Math.max(1, shutdownWaitMillis);
    }

    <T> List<T> execute(List<Callable<T>> tasks) {
        return run(tasks, naturalOrder(tasks.size())).results();
    }

    /**
     * Run every task, submitting in {@code submissionOrder} but returning results in task order.
     *
     * <p>Submission order is a scheduling hint only. Result positions follow the caller's task list
     * so reporting stays deterministic however the pool interleaves.
     */
    <T> Execution<T> run(List<Callable<T>> tasks, List<Integer> submissionOrder) {
        if (tasks.isEmpty()) {
            return new Execution<>(List.of(), 0, 0L);
        }
        int workers = concurrency.workersFor(tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        ExecutorCompletionService<Completed<T>> completions =
                new ExecutorCompletionService<>(executor);
        Set<BuildCancellation> cancellations = ConcurrentHashMap.newKeySet();
        AtomicLong queueNanos = new AtomicLong();
        long submittedAt = System.nanoTime();
        try {
            for (int position : submissionOrder) {
                int resultIndex = position;
                Callable<T> task = tasks.get(position);
                BuildCancellation cancellation = new BuildCancellation();
                cancellations.add(cancellation);
                completions.submit(() -> {
                    queueNanos.addAndGet(Math.max(0L, System.nanoTime() - submittedAt));
                    try {
                        return cancellation.call(() -> call(resultIndex, task));
                    } finally {
                        cancellations.remove(cancellation);
                    }
                });
            }
            List<T> ordered =
                    new ArrayList<>(java.util.Collections.nCopies(tasks.size(), null));
            for (int completed = 0; completed < tasks.size(); completed++) {
                Completed<T> result = await(completions);
                ordered.set(result.index(), result.value());
            }
            return new Execution<>(ordered, workers, queueNanos.get());
        } finally {
            cancellations.forEach(BuildCancellation::cancel);
            stop(executor, shutdownWaitMillis);
        }
    }

    private static List<Integer> naturalOrder(int size) {
        List<Integer> order = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            order.add(index);
        }
        return order;
    }

    private static <T> Completed<T> call(int index, Callable<T> task) {
        try {
            return new Completed<>(index, task.call());
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception exception) {
            throw new TestRunException("Workspace member test execution failed.", exception);
        }
    }

    private static void stop(
            ExecutorService executor,
            long shutdownWaitMillis) {
        executor.shutdownNow();
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (executor.awaitTermination(
                            shutdownWaitMillis,
                            TimeUnit.MILLISECONDS)) {
                        return;
                    }
                    executor.shutdownNow();
                } catch (InterruptedException exception) {
                    interrupted = true;
                    executor.shutdownNow();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static <T> Completed<T> await(
            ExecutorCompletionService<Completed<T>> completions) {
        try {
            return completions.take().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TestRunException(
                    "Workspace tests were interrupted while waiting for a member.",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new TestRunException(
                    "Workspace member test execution failed.",
                    cause);
        }
    }

    /** Results in task order, plus what the pool actually did. */
    record Execution<T>(List<T> results, int workers, long queueNanos) {
    }

    private record Completed<T>(int index, T value) {
    }
}
