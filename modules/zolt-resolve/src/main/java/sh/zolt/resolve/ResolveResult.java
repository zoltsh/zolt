package sh.zolt.resolve;

import sh.zolt.resolve.metrics.ResolveMetrics;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code warnings} carries the structured reports of a policy that mediates without rejecting —
 * today {@code [dependencies.policy].conflicts = "warn"} (design §9.11).
 */
public record ResolveResult(
        int resolvedCount,
        int downloadCount,
        int conflictCount,
        Path lockfilePath,
        ResolveMetrics metrics,
        List<String> warnings) {
    public ResolveResult(
            int resolvedCount,
            int downloadCount,
            int conflictCount,
            Path lockfilePath) {
        this(resolvedCount, downloadCount, conflictCount, lockfilePath, ResolveMetrics.empty(), List.of());
    }

    public ResolveResult(
            int resolvedCount,
            int downloadCount,
            int conflictCount,
            Path lockfilePath,
            ResolveMetrics metrics) {
        this(resolvedCount, downloadCount, conflictCount, lockfilePath, metrics, List.of());
    }

    public ResolveResult {
        metrics = metrics == null ? ResolveMetrics.empty() : metrics;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
