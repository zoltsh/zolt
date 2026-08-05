package sh.zolt.javac;

/**
 * Halts this worker JVM as soon as the supervisor that launched it dies.
 *
 * <p>A child otherwise learns of a lost broker only between compiles, when its stdin reaches EOF at
 * the next request. If the broker is killed mid-compile the child is merely reparented and would run
 * its in-flight compile to completion, writing class files into an output directory the CLI is
 * already recompiling through a command-local fallback worker — a window in which a downstream reader
 * could observe a torn class. Polling the supervisor's own process id closes that window: on the
 * supervisor's death the child halts at once.
 *
 * <p>It {@link Runtime#halt halts} rather than exits so an in-flight compile is aborted immediately,
 * without running a shutdown that could flush a half-written class. Abandoning the partial write is
 * correct: the fallback worker recompiles the same request and rewrites those outputs whole. This is
 * only the broker-crash path; a live broker still cancels by killing its own child.
 */
final class ParentWatchdog {
    private static final long POLL_MILLIS = 250L;
    static final int SUPERVISOR_LOST_EXIT_CODE = 70;

    private ParentWatchdog() {
    }

    /** Starts a daemon that halts this JVM once {@code supervisorPid} is no longer alive. */
    static void watch(long supervisorPid) {
        Thread watchdog = new Thread(() -> run(supervisorPid), "zolt-javac-worker-parent-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void run(long supervisorPid) {
        while (alive(supervisorPid)) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Runtime.getRuntime().halt(SUPERVISOR_LOST_EXIT_CODE);
    }

    private static boolean alive(long supervisorPid) {
        return ProcessHandle.of(supervisorPid).map(ProcessHandle::isAlive).orElse(false);
    }
}
