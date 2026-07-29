package sh.zolt.workspace.service;

import sh.zolt.test.runtime.TestRunException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class WorkspaceTestExecutor {
    private static final long DEFAULT_SHUTDOWN_WAIT_MILLIS =
            TimeUnit.SECONDS.toMillis(30);

    private final int maximumWorkers;
    private final long shutdownWaitMillis;

    WorkspaceTestExecutor() {
        this(Math.min(
                4,
                Runtime.getRuntime().availableProcessors()));
    }

    WorkspaceTestExecutor(int maximumWorkers) {
        this(maximumWorkers, DEFAULT_SHUTDOWN_WAIT_MILLIS);
    }

    WorkspaceTestExecutor(int maximumWorkers, long shutdownWaitMillis) {
        this.maximumWorkers = Math.max(1, maximumWorkers);
        this.shutdownWaitMillis = Math.max(1, shutdownWaitMillis);
    }

    <T> List<T> execute(List<Callable<T>> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        int workers = Math.min(tasks.size(), maximumWorkers);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        ExecutorCompletionService<Completed<T>> completions =
                new ExecutorCompletionService<>(executor);
        try {
            for (int index = 0; index < tasks.size(); index++) {
                int resultIndex = index;
                Callable<T> task = tasks.get(index);
                completions.submit(() -> new Completed<>(resultIndex, task.call()));
            }
            List<T> ordered =
                    new ArrayList<>(java.util.Collections.nCopies(tasks.size(), null));
            for (int completed = 0; completed < tasks.size(); completed++) {
                Completed<T> result = await(completions);
                ordered.set(result.index(), result.value());
            }
            return ordered;
        } finally {
            stop(executor, shutdownWaitMillis);
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

    private record Completed<T>(int index, T value) {
    }
}
