package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

final class WorkspaceReadyQueueExecutor {
    <T> Result<T> execute(
            WorkspaceBuildBatchPlanner.Plan plan,
            int maxWorkers,
            BiFunction<String, Boolean, TaskResult<T>> task) {
        if (plan.includedMembers().isEmpty()) {
            return new Result<>(Map.of(), 0L, 0);
        }
        ExecutorService executor = Executors.newFixedThreadPool(maxWorkers);
        ExecutorCompletionService<Completed<T>> completions =
                new ExecutorCompletionService<>(executor);
        Map<String, Integer> remaining = new LinkedHashMap<>(plan.dependencyCounts());
        PriorityQueue<String> ready = plan.readyMembers();
        Set<String> invalidated = new LinkedHashSet<>();
        Map<String, T> results = new LinkedHashMap<>();
        long taskNanos = 0L;
        long windowStarted = System.nanoTime();
        int running = 0;
        int readyQueuePeak = ready.size();
        try {
            while (results.size() < plan.includedMembers().size()) {
                while (running < maxWorkers && !ready.isEmpty()) {
                    String member = ready.remove();
                    boolean dependencyInvalidated = invalidated.contains(member);
                    completions.submit(
                            () -> run(member, dependencyInvalidated, task));
                    running++;
                }
                if (running == 0) {
                    throw new BuildException("Workspace ready queue stalled before all members completed.");
                }
                Completed<T> completed = await(completions);
                running--;
                results.put(completed.member(), completed.result().value());
                taskNanos += completed.durationNanos();
                if (completed.result().invalidatesDependents()) {
                    invalidated.addAll(
                            plan.dependentsByDependency().get(completed.member()));
                }
                for (String dependent : plan.dependentsByDependency().get(completed.member())) {
                    int count = remaining.get(dependent) - 1;
                    remaining.put(dependent, count);
                    if (count == 0) {
                        ready.add(dependent);
                    }
                }
                readyQueuePeak = Math.max(readyQueuePeak, ready.size());
            }
        } finally {
            executor.shutdownNow();
        }
        long windowNanos = Math.max(0L, System.nanoTime() - windowStarted);
        long capacityNanos = saturatedMultiply(windowNanos, maxWorkers);
        return new Result<>(
                results,
                Math.max(0L, capacityNanos - taskNanos),
                readyQueuePeak);
    }

    private static <T> Completed<T> run(
            String member,
            boolean dependencyInvalidated,
            BiFunction<String, Boolean, TaskResult<T>> task) {
        long started = System.nanoTime();
        TaskResult<T> result = task.apply(member, dependencyInvalidated);
        return new Completed<>(member, result, Math.max(0L, System.nanoTime() - started));
    }

    private static <T> Completed<T> await(
            ExecutorCompletionService<Completed<T>> completions) {
        try {
            return completions.take().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BuildException("Workspace build was interrupted while waiting for member compilation.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new BuildException("Workspace build failed while compiling a member.", cause);
        }
    }

    private static long saturatedMultiply(long value, int multiplier) {
        try {
            return Math.multiplyExact(value, multiplier);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private record Completed<T>(
            String member,
            TaskResult<T> result,
            long durationNanos) {
    }

    record TaskResult<T>(
            T value,
            boolean invalidatesDependents) {
    }

    record Result<T>(
            Map<String, T> resultsByMember,
            long schedulerIdleNanos,
            int readyQueuePeak) {
        Result {
            resultsByMember = Map.copyOf(resultsByMember);
        }
    }
}
