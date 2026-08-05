package sh.zolt.workspace.service;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What to plan against: a workspace a caller already discovered, or the directory to discover from.
 *
 * <p>A command that gated on lock freshness has already read and parsed every member config, so it
 * carries that snapshot into planning instead of paying for the walk twice.
 */
public record WorkspacePlanTarget(
        Optional<Workspace> discovered,
        Path startDirectory) {
    public WorkspacePlanTarget {
        discovered = discovered == null ? Optional.empty() : discovered;
    }

    public static WorkspacePlanTarget at(Path startDirectory) {
        return new WorkspacePlanTarget(Optional.empty(), startDirectory);
    }

    public static WorkspacePlanTarget of(Workspace workspace) {
        return new WorkspacePlanTarget(Optional.of(workspace), workspace.root());
    }
}
