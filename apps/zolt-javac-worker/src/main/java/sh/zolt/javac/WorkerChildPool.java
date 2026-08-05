package sh.zolt.javac;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The broker's pool of child worker JVMs, shared by every connected session.
 *
 * <p>A child is leased for exactly one request. That is what keeps cancellation cheap and safe: the
 * only children a dying session can be holding are the ones actively compiling for it, so killing
 * those stops every mutation the session could still cause, and every idle child stays warm for the
 * next command. Killed children are replaced on a background thread rather than on the request path.
 */
final class WorkerChildPool {
    private final List<String> childCommand;
    private final int maximumSize;
    private final ArrayDeque<WorkerChild> idle = new ArrayDeque<>();
    private final AtomicLong starts = new AtomicLong();
    private final ExecutorService replacements = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "zolt-javac-broker-refill");
        thread.setDaemon(true);
        return thread;
    });

    private int size;
    private int target;
    private boolean closed;

    WorkerChildPool(List<String> childCommand, int maximumSize) {
        this.childCommand = List.copyOf(childCommand);
        this.maximumSize = Math.max(1, maximumSize);
    }

    /** Leases a warm child, starting one when the pool has spare capacity. */
    Lease acquire() throws IOException, InterruptedException {
        while (true) {
            WorkerChild reused = pollIdle();
            if (reused != null) {
                return new Lease(reused, false, 0L);
            }
            if (!reserve()) {
                awaitCapacity();
                continue;
            }
            try {
                WorkerChild started = WorkerChild.start(childCommand);
                starts.incrementAndGet();
                return new Lease(started, true, started.startupNanos());
            } catch (IOException exception) {
                unreserve();
                throw exception;
            }
        }
    }

    /** Returns a healthy child to the warm set. */
    void release(Lease lease) {
        WorkerChild child = lease.child();
        synchronized (this) {
            if (!closed && child.isAlive()) {
                idle.addLast(child);
                notifyAll();
                return;
            }
            size--;
            notifyAll();
        }
        child.destroy();
    }

    /** Kills a child, which is the broker's only authoritative way to stop a compile. */
    void discard(Lease lease) {
        boolean refill;
        synchronized (this) {
            size--;
            refill = !closed && size < target;
            notifyAll();
        }
        lease.child().destroy();
        if (refill) {
            scheduleRefill();
        }
    }

    /** Raises the warm-child target and starts the missing children off the request path. */
    void prewarm(int count) {
        synchronized (this) {
            if (closed) {
                return;
            }
            target = Math.max(target, Math.min(count, maximumSize));
            if (size >= target) {
                return;
            }
        }
        scheduleRefill();
    }

    long starts() {
        return starts.get();
    }

    synchronized int size() {
        return size;
    }

    /** Children that are started and waiting, as opposed to slots reserved for a starting child. */
    synchronized int idleSize() {
        return idle.size();
    }

    void close() {
        List<WorkerChild> doomed;
        synchronized (this) {
            closed = true;
            doomed = new ArrayList<>(idle);
            idle.clear();
            size -= doomed.size();
            notifyAll();
        }
        replacements.shutdownNow();
        doomed.forEach(WorkerChild::destroy);
    }

    private synchronized WorkerChild pollIdle() {
        while (true) {
            WorkerChild child = idle.pollFirst();
            if (child == null) {
                return null;
            }
            if (child.isAlive()) {
                return child;
            }
            size--;
            child.destroy();
        }
    }

    private synchronized boolean reserve() throws InterruptedException {
        if (closed) {
            throw new InterruptedException("javac broker is shutting down");
        }
        if (size >= maximumSize) {
            return false;
        }
        size++;
        return true;
    }

    private synchronized void unreserve() {
        size--;
        notifyAll();
    }

    private synchronized void awaitCapacity() throws InterruptedException {
        if (!closed && idle.isEmpty() && size >= maximumSize) {
            wait(1_000L);
        }
    }

    private void scheduleRefill() {
        try {
            replacements.execute(this::refill);
        } catch (RuntimeException ignored) {
            // A shutting-down broker does not need replacements.
        }
    }

    private void refill() {
        while (true) {
            synchronized (this) {
                if (closed || size >= target) {
                    return;
                }
                size++;
            }
            try {
                WorkerChild child = WorkerChild.start(childCommand);
                starts.incrementAndGet();
                synchronized (this) {
                    idle.addLast(child);
                    notifyAll();
                }
            } catch (IOException exception) {
                unreserve();
                return;
            }
        }
    }

    /** One child, held for the duration of a single request. */
    record Lease(WorkerChild child, boolean started, long startupNanos) {
    }
}
