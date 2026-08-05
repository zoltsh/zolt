package sh.zolt.build.compile;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide javac worker counters for one command, published into {@code --timings} attributes.
 *
 * <p>{@code starts} is the number of child JVMs the broker had to launch to serve this command and
 * {@code reuses} the number of requests a warm child answered, so a second identical command should
 * report zero starts. The nanosecond totals are sums across concurrent requests, not wall time.
 */
public final class JavacWorkerMetrics {
    private static final AtomicLong STARTS = new AtomicLong();
    private static final AtomicLong REUSES = new AtomicLong();
    private static final AtomicLong STARTUP_NANOS = new AtomicLong();
    private static final AtomicLong QUEUE_NANOS = new AtomicLong();
    private static final AtomicLong REQUEST_NANOS = new AtomicLong();
    private static final AtomicLong BROKER_SESSIONS = new AtomicLong();
    private static final AtomicLong BROKER_REQUESTS = new AtomicLong();
    private static final AtomicLong LOCAL_REQUESTS = new AtomicLong();

    private JavacWorkerMetrics() {
    }

    static void recordBrokerRequest(boolean started, long startupNanos, long queueNanos, long requestNanos) {
        (started ? STARTS : REUSES).incrementAndGet();
        STARTUP_NANOS.addAndGet(Math.max(0L, startupNanos));
        QUEUE_NANOS.addAndGet(Math.max(0L, queueNanos));
        REQUEST_NANOS.addAndGet(Math.max(0L, requestNanos));
        BROKER_REQUESTS.incrementAndGet();
    }

    static void recordBrokerSession() {
        BROKER_SESSIONS.incrementAndGet();
    }

    /** A compile served by a command-local worker, meaning the broker was unavailable or declined. */
    static void recordLocalRequest(boolean started) {
        (started ? STARTS : REUSES).incrementAndGet();
        LOCAL_REQUESTS.incrementAndGet();
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                STARTS.get(),
                REUSES.get(),
                STARTUP_NANOS.get(),
                QUEUE_NANOS.get(),
                REQUEST_NANOS.get(),
                BROKER_SESSIONS.get(),
                BROKER_REQUESTS.get(),
                LOCAL_REQUESTS.get());
    }

    /** Resets the counters, for tests that measure one command at a time. */
    public static void reset() {
        STARTS.set(0);
        REUSES.set(0);
        STARTUP_NANOS.set(0);
        QUEUE_NANOS.set(0);
        REQUEST_NANOS.set(0);
        BROKER_SESSIONS.set(0);
        BROKER_REQUESTS.set(0);
        LOCAL_REQUESTS.set(0);
    }

    public record Snapshot(
            long starts,
            long reuses,
            long startupNanos,
            long queueNanos,
            long requestNanos,
            long brokerSessions,
            long brokerRequests,
            long localRequests) {
    }
}
