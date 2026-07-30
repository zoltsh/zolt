package sh.zolt.cancel;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Cancellation token installed around one concurrent build task. Blocking resources register a close
 * action so the scheduler can stop them authoritatively before waiting for the task thread.
 */
public final class BuildCancellation {
    private static final ThreadLocal<BuildCancellation> CURRENT = new InheritableThreadLocal<>();

    private final CopyOnWriteArrayList<CancellationAction> actions =
            new CopyOnWriteArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public <T> T call(Supplier<T> task) {
        Objects.requireNonNull(task, "Build cancellation task is required.");
        BuildCancellation previous = CURRENT.get();
        CURRENT.set(this);
        try {
            if (cancelled.get()) {
                throw new CancellationException("Concurrent build task was cancelled before it started.");
            }
            return task.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            actions.forEach(CancellationAction::dismiss);
            actions.clear();
        }
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        actions.forEach(CancellationAction::cancel);
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    public static Registration onCancel(Runnable action) {
        Objects.requireNonNull(action, "Build cancellation action is required.");
        BuildCancellation cancellation = CURRENT.get();
        if (cancellation == null) {
            return () -> {};
        }
        CancellationAction registered = new CancellationAction(action);
        cancellation.actions.add(registered);
        if (cancellation.cancelled.get()) {
            registered.cancel();
        }
        return registered::dismiss;
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private static final class CancellationAction {
        private final Runnable action;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private CancellationAction(Runnable action) {
            this.action = action;
        }

        private void cancel() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            try {
                action.run();
            } catch (RuntimeException ignored) {
                // Continue cancelling the task's remaining resources.
            }
        }

        private void dismiss() {
            active.set(false);
        }
    }
}
