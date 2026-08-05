package sh.zolt.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class WorkerChildPoolTest {
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

    private WorkerChildPool pool;

    @AfterEach
    void closePool() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    void prewarmStartsChildrenOffTheRequestPath() throws Exception {
        pool = new WorkerChildPool(childCommand(), 4);

        pool.prewarm(2);
        awaitIdle(2);
        assertEquals(2L, pool.starts(), "prewarm starts the children");

        WorkerChildPool.Lease first = pool.acquire();
        WorkerChildPool.Lease second = pool.acquire();

        assertEquals(2L, pool.starts(), "a request must not have to boot a JVM after a prewarm");
        assertNotEquals(first.child().pid(), second.child().pid());
    }

    @Test
    void aReleasedChildIsLeasedAgainInsteadOfStartingANewOne() throws Exception {
        pool = new WorkerChildPool(childCommand(), 4);

        WorkerChildPool.Lease first = pool.acquire();
        assertTrue(first.started());
        long pid = first.child().pid();
        pool.release(first);

        WorkerChildPool.Lease second = pool.acquire();

        assertFalse(second.started());
        assertEquals(pid, second.child().pid());
        assertEquals(1L, pool.starts());
    }

    @Test
    void discardingALeaseKillsTheChildAndRefillsToTheWarmTarget() throws Exception {
        pool = new WorkerChildPool(childCommand(), 4);
        pool.prewarm(1);
        awaitIdle(1);
        WorkerChildPool.Lease lease = pool.acquire();
        long pid = lease.child().pid();

        pool.discard(lease);

        assertTrue(exited(pid), "a discarded child must actually be killed");
        awaitIdle(1);
    }

    /**
     * Waits on the pool's own monitor, which every state change notifies, so a warm child is observed
     * the instant it is added rather than at the next poll.
     */
    private void awaitIdle(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        synchronized (pool) {
            while (pool.idleSize() < expected) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.timedWait(pool, remaining);
            }
        }
        assertTrue(pool.idleSize() >= expected, "pool never reached its warm target");
    }

    private static boolean exited(long pid) throws Exception {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            return true;
        }
        try {
            handle.orElseThrow().onExit().get(30, TimeUnit.SECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException exception) {
            return false;
        }
    }

    /** A real child JVM that blocks reading its request, which is all the pool itself cares about. */
    private static List<String> childCommand() {
        return List.of(
                BrokerTestHarness.javaExecutable(),
                "-classpath",
                BrokerTestHarness.classpathFor(JavacWorkerMain.class),
                JavacWorkerMain.class.getName(),
                JavacWorkerMain.FRAMED_FLAG);
    }
}
