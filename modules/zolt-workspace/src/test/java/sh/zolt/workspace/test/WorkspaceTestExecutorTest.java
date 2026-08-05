package sh.zolt.workspace.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class WorkspaceTestExecutorTest {
    @Test
    void boundsConcurrencyAndPreservesTaskOrder() throws Exception {
        WorkspaceTestExecutor executor = new WorkspaceTestExecutor(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<Callable<Integer>> tasks = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> (Callable<Integer>) () -> {
                    int current = active.incrementAndGet();
                    peak.accumulateAndGet(current, Math::max);
                    started.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    active.decrementAndGet();
                    return index;
                })
                .toList();

        CompletableFuture<List<Integer>> execution =
                CompletableFuture.supplyAsync(() -> executor.execute(tasks));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        release.countDown();

        assertEquals(List.of(0, 1, 2, 3), execution.get(5, TimeUnit.SECONDS));
        assertEquals(2, peak.get());
    }

    @Test
    void waitsForCancelledTasksBeforeReturningFailure() {
        WorkspaceTestExecutor executor = new WorkspaceTestExecutor(2);
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

    @Test
    void keepsWaitingWhenCancelledTaskIgnoresInitialInterrupt() throws Exception {
        WorkspaceTestExecutor executor = new WorkspaceTestExecutor(2, 10);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch firstFailed = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);

        CompletableFuture<Void> execution = CompletableFuture.runAsync(() ->
                assertThrows(IllegalStateException.class, () -> executor.execute(List.of(
                        () -> {
                            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                            firstFailed.countDown();
                            throw new IllegalStateException("first failed");
                        },
                        () -> {
                            secondStarted.countDown();
                            awaitIgnoringInterrupts(releaseSecond);
                            return 2;
                        }))));

        assertTrue(firstFailed.await(5, TimeUnit.SECONDS));
        assertThrows(
                TimeoutException.class,
                () -> execution.get(100, TimeUnit.MILLISECONDS));
        releaseSecond.countDown();
        execution.get(5, TimeUnit.SECONDS);
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {
                // Keep running until the task's own cleanup is complete.
            }
        }
    }
}
