package sh.zolt.workspace.state;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public record WorkspaceState(Map<String, WorkspaceMemberState> members) {
    public WorkspaceState {
        members = Map.copyOf(new LinkedHashMap<>(members));
    }

    public static WorkspaceState empty() {
        return new WorkspaceState(Map.of());
    }

    public Optional<WorkspaceMemberState> member(String member) {
        return Optional.ofNullable(members.get(member));
    }
}
