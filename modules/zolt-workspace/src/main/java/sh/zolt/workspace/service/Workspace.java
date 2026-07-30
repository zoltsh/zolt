package sh.zolt.workspace.service;

import sh.zolt.workspace.WorkspaceConfig;
import java.nio.file.Path;
import java.util.List;

public record Workspace(
        Path root,
        Path configPath,
        WorkspaceConfig config,
        List<WorkspaceMember> members,
        List<WorkspaceProjectEdge> edges,
        List<String> buildOrder,
        WorkspaceInputs inputs) {
    public Workspace {
        members = List.copyOf(members);
        edges = List.copyOf(edges);
        buildOrder = List.copyOf(buildOrder);
        inputs = inputs == null ? WorkspaceInputs.unchecked() : inputs;
    }

    public Workspace(
            Path root,
            Path configPath,
            WorkspaceConfig config,
            List<WorkspaceMember> members,
            List<WorkspaceProjectEdge> edges,
            List<String> buildOrder) {
        this(root, configPath, config, members, edges, buildOrder, WorkspaceInputs.unchecked());
    }

    public Workspace(
            Path root,
            Path configPath,
            WorkspaceConfig config,
            List<WorkspaceMember> members,
            List<WorkspaceProjectEdge> edges) {
        this(root, configPath, config, members, edges, memberPaths(members), WorkspaceInputs.unchecked());
    }

    public Workspace(
            Path root,
            Path configPath,
            WorkspaceConfig config,
            List<WorkspaceMember> members) {
        this(root, configPath, config, members, List.of());
    }

    public Workspace withInputs(WorkspaceInputs workspaceInputs) {
        return new Workspace(
                root,
                configPath,
                config,
                members,
                edges,
                buildOrder,
                workspaceInputs);
    }

    private static List<String> memberPaths(List<WorkspaceMember> members) {
        return members.stream()
                .map(WorkspaceMember::path)
                .toList();
    }
}
