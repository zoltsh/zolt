package sh.zolt.resolve;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.resolve.metrics.ResolveMetrics;
import java.util.List;

public record ResolveOutput(
        ZoltLockfile lockfile,
        int downloadCount,
        ResolveMetrics metrics,
        List<ResolvedDependencyReachability> dependencyReachability) {
    public ResolveOutput(ZoltLockfile lockfile, int downloadCount) {
        this(lockfile, downloadCount, ResolveMetrics.empty(), List.of());
    }

    public ResolveOutput(
            ZoltLockfile lockfile,
            int downloadCount,
            ResolveMetrics metrics) {
        this(lockfile, downloadCount, metrics, List.of());
    }

    public ResolveOutput {
        metrics = metrics == null ? ResolveMetrics.empty() : metrics;
        dependencyReachability = dependencyReachability == null
                ? List.of()
                : List.copyOf(dependencyReachability);
    }
}
