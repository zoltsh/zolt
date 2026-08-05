package sh.zolt.workspace.testpool;

import sh.zolt.test.runtime.TestRunException;

/**
 * How many workspace members run their tests at the same time.
 *
 * <p>Each member forks its own test JVM, so the lane is dominated by process startup rather than by
 * steady-state CPU work. Sizing the pool at the core count therefore leaves cores idle while workers
 * boot. The adaptive default deliberately oversubscribes the machine so that booting workers overlap
 * with running ones.
 */
public final class WorkspaceTestConcurrency {
    /** Upper bound on concurrent member test JVMs, whatever the core count or the request. */
    public static final int MAX_WORKERS = 64;

    private static final String FLAG = "--test-workers";
    private static final int OVERSUBSCRIPTION_NUMERATOR = 3;
    private static final int OVERSUBSCRIPTION_DENOMINATOR = 2;
    private static final int MINIMUM_ADAPTIVE_WORKERS = 2;

    private static final WorkspaceTestConcurrency ADAPTIVE = new WorkspaceTestConcurrency(0);

    private final int requested;

    private WorkspaceTestConcurrency(int requested) {
        this.requested = requested;
    }

    /** Scale with the machine and the member count. */
    public static WorkspaceTestConcurrency adaptive() {
        return ADAPTIVE;
    }

    /** Pin the pool to an explicit width. */
    public static WorkspaceTestConcurrency of(int workers) {
        return new WorkspaceTestConcurrency(validated(workers, Integer.toString(workers)));
    }

    /** Parse a {@code --test-workers} value; {@code null} or blank keeps the adaptive default. */
    public static WorkspaceTestConcurrency fromCli(String value) {
        if (value == null || value.isBlank()) {
            return ADAPTIVE;
        }
        String trimmed = value.trim();
        int parsed;
        try {
            parsed = Integer.parseInt(trimmed);
        } catch (NumberFormatException exception) {
            throw new TestRunException(invalid(trimmed), exception);
        }
        return new WorkspaceTestConcurrency(validated(parsed, trimmed));
    }

    /** True when no explicit width was requested. */
    public boolean isAdaptive() {
        return requested == 0;
    }

    /** Resolve the pool width for this run against the detected core count. */
    public int workersFor(int memberCount) {
        return workersFor(memberCount, Runtime.getRuntime().availableProcessors());
    }

    /** Resolve the pool width for this run. Never exceeds the member count. */
    public int workersFor(int memberCount, int availableProcessors) {
        int members = Math.max(1, memberCount);
        int workers = requested > 0 ? requested : adaptiveWorkers(availableProcessors);
        return Math.min(members, workers);
    }

    /**
     * The adaptive width before it is clamped to the member count.
     *
     * <p>Oversubscribes the core count by half because member test JVMs spend most of their life
     * starting up rather than burning CPU.
     */
    public static int adaptiveWorkers(int availableProcessors) {
        int cores = Math.max(1, availableProcessors);
        int scaled = cores * OVERSUBSCRIPTION_NUMERATOR / OVERSUBSCRIPTION_DENOMINATOR;
        return Math.min(MAX_WORKERS, Math.max(MINIMUM_ADAPTIVE_WORKERS, scaled));
    }

    private static int validated(int workers, String display) {
        if (workers < 1) {
            throw new TestRunException(invalid(display));
        }
        if (workers > MAX_WORKERS) {
            throw new TestRunException(
                    "Invalid " + FLAG + " `" + display + "`. Use a value between 1 and "
                            + MAX_WORKERS + ".");
        }
        return workers;
    }

    private static String invalid(String display) {
        return "Invalid " + FLAG + " `" + display + "`. Use a positive integer.";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WorkspaceTestConcurrency concurrency
                && concurrency.requested == requested;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(requested);
    }

    @Override
    public String toString() {
        return isAdaptive() ? "adaptive" : Integer.toString(requested);
    }
}
