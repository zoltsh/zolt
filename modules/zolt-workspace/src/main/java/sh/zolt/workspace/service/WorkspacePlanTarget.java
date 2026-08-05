package sh.zolt.workspace.service;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What to plan against: a workspace a caller already discovered, or the directory to discover from.
 *
 * <p>A command that gated on lock freshness has already read and parsed every member config, so it
 * carries that snapshot into planning instead of paying for the walk twice — along with
 * {@code discoveryNanos}, what the walk cost, so moving the work out of planning does not turn the
 * discovery counter into a zero.
 */
public record WorkspacePlanTarget(
        Optional<Workspace> discovered,
        Path startDirectory,
        long discoveryNanos) {
    public WorkspacePlanTarget {
        discovered = discovered == null ? Optional.empty() : discovered;
        discoveryNanos = Math.max(0L, discoveryNanos);
    }

    public static WorkspacePlanTarget at(Path startDirectory) {
        return new WorkspacePlanTarget(Optional.empty(), startDirectory, 0L);
    }

    public static WorkspacePlanTarget of(Workspace workspace, long discoveryNanos) {
        return new WorkspacePlanTarget(
                Optional.of(workspace), workspace.root(), discoveryNanos);
    }
}
