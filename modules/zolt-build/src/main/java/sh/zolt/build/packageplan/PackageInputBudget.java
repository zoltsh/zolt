package sh.zolt.build.packageplan;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounds how many package-input bytes a command may hold in memory at once.
 *
 * <p>Small files are kept in memory so that the fingerprint read is also the archive read. Large
 * files are streamed instead, so a single oversized resource cannot turn packaging into a heap
 * problem. Three limits apply: a per-file cap, a per-snapshot cap, and a command-wide live cap.
 */
public final class PackageInputBudget {
    private static final long DEFAULT_MAX_FILE_BYTES = 1L << 20;
    private static final long DEFAULT_MAX_SNAPSHOT_BYTES = 32L << 20;
    private static final long DEFAULT_MAX_LIVE_BYTES = 256L << 20;
    private static final int HEAP_SHARE_DIVISOR = 8;

    private final long maxFileBytes;
    private final long maxSnapshotBytes;
    private final AtomicLong liveBytesAvailable;

    PackageInputBudget(long maxFileBytes, long maxSnapshotBytes, long maxLiveBytes) {
        this.maxFileBytes = Math.max(0L, maxFileBytes);
        this.maxSnapshotBytes = Math.max(0L, maxSnapshotBytes);
        this.liveBytesAvailable = new AtomicLong(Math.max(0L, maxLiveBytes));
    }

    public static PackageInputBudget defaults() {
        return new PackageInputBudget(
                DEFAULT_MAX_FILE_BYTES,
                DEFAULT_MAX_SNAPSHOT_BYTES,
                Math.min(
                        DEFAULT_MAX_LIVE_BYTES,
                        Runtime.getRuntime().maxMemory() / HEAP_SHARE_DIVISOR));
    }

    /**
     * A budget that always streams, for callers that only need identities.
     */
    public static PackageInputBudget streaming() {
        return new PackageInputBudget(0L, 0L, 0L);
    }

    public static PackageInputBudget of(long maxFileBytes, long maxSnapshotBytes, long maxLiveBytes) {
        return new PackageInputBudget(maxFileBytes, maxSnapshotBytes, maxLiveBytes);
    }

    long maxFileBytes() {
        return maxFileBytes;
    }

    long maxSnapshotBytes() {
        return maxSnapshotBytes;
    }

    boolean claim(long bytes) {
        if (bytes <= 0) {
            return true;
        }
        while (true) {
            long available = liveBytesAvailable.get();
            if (available < bytes) {
                return false;
            }
            if (liveBytesAvailable.compareAndSet(available, available - bytes)) {
                return true;
            }
        }
    }

    void refund(long bytes) {
        if (bytes > 0) {
            liveBytesAvailable.addAndGet(bytes);
        }
    }
}
