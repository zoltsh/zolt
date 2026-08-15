package sh.zolt.workspace.service;

import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import java.nio.file.Path;
import java.util.Optional;

/**
 * What to plan against: a workspace a caller already discovered, or the directory to discover from.
 *
 * <p>A command that gated on lock freshness has already read and parsed every member config, so it
 * carries that snapshot into planning instead of paying for the walk twice — along with
 * {@code discoveryNanos}, what the walk cost, and the populated artifact index, so moving the work
 * out of planning does not turn the discovery counter into a zero or duplicate integrity I/O.
 */
public record WorkspacePlanTarget(
        Optional<Workspace> discovered,
        Path startDirectory,
        long discoveryNanos,
        VerifiedArtifactIndex artifactIndex) {
    public WorkspacePlanTarget {
        discovered = discovered == null ? Optional.empty() : discovered;
        discoveryNanos = Math.max(0L, discoveryNanos);
        artifactIndex = artifactIndex == null ? new VerifiedArtifactIndex() : artifactIndex;
    }

    public static WorkspacePlanTarget at(Path startDirectory) {
        return new WorkspacePlanTarget(
                Optional.empty(), startDirectory, 0L, new VerifiedArtifactIndex());
    }

    public static WorkspacePlanTarget of(
            Workspace workspace,
            long discoveryNanos,
            VerifiedArtifactIndex artifactIndex) {
        return new WorkspacePlanTarget(
                Optional.of(workspace), workspace.root(), discoveryNanos, artifactIndex);
    }

    public static WorkspacePlanTarget of(Workspace workspace, long discoveryNanos) {
        return of(workspace, discoveryNanos, new VerifiedArtifactIndex());
    }
}
