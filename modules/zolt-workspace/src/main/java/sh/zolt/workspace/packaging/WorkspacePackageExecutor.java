package sh.zolt.workspace.packaging;

import sh.zolt.build.PackageException;
import sh.zolt.cancel.BuildCancellation;
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

final class WorkspacePackageExecutor {
    private static final long DEFAULT_SHUTDOWN_WAIT_MILLIS =
            TimeUnit.SECONDS.toMillis(30);

    /**
     * Packaging a member is dominated by filesystem metadata work — create, write, rename, digest —
     * not by compression, so extra threads buy contention rather than throughput. Measured on a
     * 203-member workspace: 4 and 6 workers tie, 8 is marginally slower, and 14 is 45 % slower with
     * 3.5x the system time. The cap stays low on purpose.
     */
    private static final int DEFAULT_MAXIMUM_WORKERS = 4;

    private final int maximumWorkers;
    private final long shutdownWaitMillis;

    WorkspacePackageExecutor() {
        this(Math.min(
                DEFAULT_MAXIMUM_WORKERS,
                Runtime.getRuntime().availableProcessors()));
    }

    WorkspacePackageExecutor(int maximumWorkers) {
        this(maximumWorkers, DEFAULT_SHUTDOWN_WAIT_MILLIS);
    }

    WorkspacePackageExecutor(int maximumWorkers, long shutdownWaitMillis) {
        this.maximumWorkers = Math.max(1, maximumWorkers);
        this.shutdownWaitMillis = Math.max(1, shutdownWaitMillis);
    }

    <T> Result<T> execute(List<Callable<T>> tasks) {
        if (tasks.isEmpty()) {
            return new Result<>(List.of(), 0);
        }
        int workers = Math.min(tasks.size(), maximumWorkers);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        ExecutorCompletionService<Completed<T>> completions =
                new ExecutorCompletionService<>(executor);
        Set<BuildCancellation> cancellations = ConcurrentHashMap.newKeySet();
        try {
            for (int index = 0; index < tasks.size(); index++) {
                int resultIndex = index;
                Callable<T> task = tasks.get(index);
                BuildCancellation cancellation = new BuildCancellation();
                cancellations.add(cancellation);
                completions.submit(() -> {
                    try {
                        return cancellation.call(() -> call(resultIndex, task));
                    } finally {
                        cancellations.remove(cancellation);
                    }
                });
            }
            List<T> ordered = new ArrayList<>(java.util.Collections.nCopies(tasks.size(), null));
            for (int completed = 0; completed < tasks.size(); completed++) {
                Completed<T> result = await(completions);
                ordered.set(result.index(), result.value());
            }
            return new Result<>(ordered, workers);
        } finally {
            cancellations.forEach(BuildCancellation::cancel);
            stop(executor, shutdownWaitMillis);
        }
    }

    private static <T> Completed<T> call(int index, Callable<T> task) {
        try {
            return new Completed<>(index, task.call());
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception exception) {
            throw new PackageException("Workspace member packaging failed.", exception);
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
            throw new PackageException(
                    "Workspace packaging was interrupted while waiting for a member.",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new PackageException("Workspace member packaging failed.", cause);
        }
    }

    private record Completed<T>(int index, T value) {
    }

    record Result<T>(List<T> values, int maxWorkers) {
        Result {
            values = List.copyOf(values);
        }
    }
}
