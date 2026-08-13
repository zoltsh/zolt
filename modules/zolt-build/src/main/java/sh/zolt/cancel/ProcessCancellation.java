package sh.zolt.cancel;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Registers a child process for graceful-then-forced task cancellation. */
public final class ProcessCancellation {
    private static final long DESTROY_GRACE_MILLIS = 250L;

    private ProcessCancellation() {
    }

    public static BuildCancellation.Registration register(Process process) {
        return BuildCancellation.onCancel(() -> terminate(process));
    }

    public static BuildCancellation.Registration register(
            Process process,
            Runnable beforeTermination) {
        return BuildCancellation.onCancel(() -> {
            beforeTermination.run();
            terminate(process);
        });
    }

    public static void terminate(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        boolean interrupted = false;
        try {
            interrupted = !waitForExit(process.toHandle(), descendants, DESTROY_GRACE_MILLIS);
            descendants.stream()
                    .filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            interrupted |= !waitForExit(process.toHandle(), descendants, DESTROY_GRACE_MILLIS);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean waitForExit(
            ProcessHandle process,
            List<ProcessHandle> descendants,
            long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return true;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(
                        remaining,
                        TimeUnit.MILLISECONDS.toNanos(10L)));
            } catch (InterruptedException exception) {
                return false;
            }
        }
        return true;
    }
}
