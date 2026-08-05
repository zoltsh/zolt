package sh.zolt.workspace.state;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a command needs to know about what the previous command saw: the per-member digests
 * that decide dirtiness, and the per-file table that lets those digests be recomputed without
 * re-reading unchanged bytes.
 */
public record WorkspaceState(Map<String, WorkspaceMemberState> members, WorkspaceFileState files) {
    public WorkspaceState {
        members = Map.copyOf(new LinkedHashMap<>(members));
    }

    public WorkspaceState(Map<String, WorkspaceMemberState> members) {
        this(members, WorkspaceFileState.empty());
    }

    public static WorkspaceState empty() {
        return new WorkspaceState(Map.of(), WorkspaceFileState.empty());
    }

    public Optional<WorkspaceMemberState> member(String member) {
        return Optional.ofNullable(members.get(member));
    }

    /** The same member rows behind a freshly observed file table. */
    public WorkspaceState withFiles(WorkspaceFileState observed) {
        return new WorkspaceState(members, observed);
    }
}
