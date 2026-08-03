package sh.zolt.cancel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class BuildCancellationTest {
    @Test
    void invokesRegisteredActionsOnce() throws Exception {
        BuildCancellation cancellation = new BuildCancellation();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Thread task = Thread.ofPlatform().start(() ->
                cancellation.call(() -> {
                    try (BuildCancellation.Registration ignored =
                            BuildCancellation.onCancel(() -> {
                                calls.incrementAndGet();
                                released.countDown();
                            })) {
                        registered.countDown();
                        await(released);
                    }
                    return null;
                }));

        assertTrue(registered.await(2, TimeUnit.SECONDS));
        cancellation.cancel();
        cancellation.cancel();
        task.join(TimeUnit.SECONDS.toMillis(2));

        assertFalse(task.isAlive());
        assertEquals(1, calls.get());
    }

    @Test
    void dismissesCompletedResources() {
        BuildCancellation cancellation = new BuildCancellation();
        AtomicInteger calls = new AtomicInteger();

        cancellation.call(() -> {
            try (BuildCancellation.Registration ignored =
                    BuildCancellation.onCancel(calls::incrementAndGet)) {
                assertTrue(BuildCancellation.active());
            }
            return null;
        });
        cancellation.cancel();

        assertFalse(BuildCancellation.active());
        assertEquals(0, calls.get());
    }

    @Test
    void propagatesCancellationToChildWorkerThreads() {
        BuildCancellation cancellation = new BuildCancellation();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);

        cancellation.call(() -> {
            Thread child = Thread.ofPlatform().start(() -> {
                try (BuildCancellation.Registration ignored =
                        BuildCancellation.onCancel(released::countDown)) {
                    registered.countDown();
                    await(released);
                }
            });
            await(registered);
            cancellation.cancel();
            join(child);
            return null;
        });

        assertEquals(0L, released.getCount());
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
        assertFalse(thread.isAlive());
    }
}
