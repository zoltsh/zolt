package sh.zolt.workspace.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class WorkspacePackageExecutorTest {
    @Test
    void runsWithBoundedParallelismAndPreservesPlanOrder() {
        WorkspacePackageExecutor executor = new WorkspacePackageExecutor(2);
        CountDownLatch concurrent = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        WorkspacePackageExecutor.Result<Integer> result = executor.execute(List.of(
                task(1, concurrent, active, peak),
                task(2, concurrent, active, peak),
                () -> 3));

        assertEquals(List.of(1, 2, 3), result.values());
        assertEquals(2, result.maxWorkers());
        assertEquals(2, peak.get());
    }

    @Test
    void waitsForCancelledTasksBeforeReturningFailure() {
        WorkspacePackageExecutor executor = new WorkspacePackageExecutor(2);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicBoolean secondStopped = new AtomicBoolean();

        assertThrows(IllegalStateException.class, () -> executor.execute(List.of(
                () -> {
                    assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                    throw new IllegalStateException("first failed");
                },
                () -> {
                    secondStarted.countDown();
                    try {
                        neverReleased.await();
                    } finally {
                        secondStopped.set(true);
                    }
                    return 2;
                })));

        assertTrue(secondStopped.get());
    }

    private static Callable<Integer> task(
            int value,
            CountDownLatch concurrent,
            AtomicInteger active,
            AtomicInteger peak) {
        return () -> {
            int running = active.incrementAndGet();
            peak.accumulateAndGet(running, Math::max);
            concurrent.countDown();
            assertTrue(concurrent.await(5, TimeUnit.SECONDS));
            active.decrementAndGet();
            return value;
        };
    }
}
