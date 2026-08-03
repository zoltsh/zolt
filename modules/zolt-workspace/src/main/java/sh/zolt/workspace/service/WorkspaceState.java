package sh.zolt.workspace.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

record WorkspaceState(Map<String, WorkspaceMemberState> members) {
    WorkspaceState {
        members = Map.copyOf(new LinkedHashMap<>(members));
    }

    static WorkspaceState empty() {
        return new WorkspaceState(Map.of());
    }

    Optional<WorkspaceMemberState> member(String member) {
        return Optional.ofNullable(members.get(member));
    }
}
