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

    private static void terminate(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        boolean interrupted = false;
        try {
            try {
                process.waitFor(DESTROY_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
            descendants.stream()
                    .filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
