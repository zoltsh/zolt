package sh.zolt.cli.command;

import sh.zolt.build.compile.JavacWorkerMetrics;
import java.util.Map;

/**
 * Publishes the command's javac worker counters into {@code --timings} attributes.
 *
 * <p>The counters are process-wide rather than carried on a result, because every compile route —
 * broker, command-local worker pool, in-process compiler — funnels through the same static seam and a
 * command is one process. {@code javacWorkerStarts} is the number a warm broker should drive to zero
 * on a repeated build.
 */
public final class CommandJavacWorkerAttributes {
    private CommandJavacWorkerAttributes() {
    }

    public static void add(Map<String, String> attributes) {
        JavacWorkerMetrics.Snapshot snapshot = JavacWorkerMetrics.snapshot();
        attributes.put(CommandAttributeKeys.JAVAC_WORKER_STARTS, Long.toString(snapshot.starts()));
        attributes.put(CommandAttributeKeys.JAVAC_WORKER_REUSES, Long.toString(snapshot.reuses()));
        attributes.put(CommandAttributeKeys.JAVAC_WORKER_STARTUP_NANOS, Long.toString(snapshot.startupNanos()));
        attributes.put(CommandAttributeKeys.JAVAC_WORKER_QUEUE_NANOS, Long.toString(snapshot.queueNanos()));
        attributes.put(CommandAttributeKeys.JAVAC_WORKER_REQUEST_NANOS, Long.toString(snapshot.requestNanos()));
        attributes.put(CommandAttributeKeys.JAVAC_BROKER_SESSIONS, Long.toString(snapshot.brokerSessions()));
        attributes.put(CommandAttributeKeys.JAVAC_BROKER_REQUESTS, Long.toString(snapshot.brokerRequests()));
        attributes.put(CommandAttributeKeys.JAVAC_LOCAL_REQUESTS, Long.toString(snapshot.localRequests()));
    }
}
