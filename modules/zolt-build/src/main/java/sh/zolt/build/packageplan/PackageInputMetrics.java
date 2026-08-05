package sh.zolt.build.packageplan;

/**
 * What one package command read from compiled outputs.
 *
 * <p>{@code filesRead} counts opens, not entries: every archive entry is written from the bytes of
 * that single read, so the ratio of entries to reads is the evidence that the pipeline is one-pass.
 *
 * @param snapshots distinct output directories walked
 * @param filesRead files opened while snapshotting those directories
 * @param bytesRead bytes read from them
 */
public record PackageInputMetrics(int snapshots, long filesRead, long bytesRead) {
    public static PackageInputMetrics empty() {
        return new PackageInputMetrics(0, 0L, 0L);
    }
}
