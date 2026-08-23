package sh.zolt.workspace.service;

import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.lockfile.ProjectLockfile;
import sh.zolt.workspace.WorkspaceConfig;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code staleExclusions} carries the authored {@code [workspace.members].exclude} patterns that
 * matched no expanded candidate. Design §6.2 allows them and requires them to be reported, so the
 * legacy projection keeps them rather than dropping discovery evidence on the floor.
 */
public record Workspace(
        Path root,
        Path configPath,
        WorkspaceConfig config,
        List<WorkspaceMember> members,
        List<WorkspaceProjectEdge> edges,
        List<String> buildOrder,
        WorkspaceInputs inputs,
        List<String> staleExclusions) {
    public Workspace {
        members = List.copyOf(members);
        edges = List.copyOf(edges);
        buildOrder = List.copyOf(buildOrder);
        inputs = inputs == null ? WorkspaceInputs.unchecked() : inputs;
        staleExclusions = staleExclusions == null ? List.of() : List.copyOf(staleExclusions);
    }

    public Workspace(
            Path root,
            Path configPath,
            WorkspaceConfig config,
            List<WorkspaceMember> members,
            List<WorkspaceProjectEdge> edges,
            List<String> buildOrder,
            WorkspaceInputs inputs) {
        this(root, configPath, config, members, edges, buildOrder, inputs, List.of());
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
                workspaceInputs,
                staleExclusions);
    }

    /**
     * The workspace's one authoritative lockfile (design §4.5/§6.8). The workspace root is where lock
     * ownership is established, so this is the only place the workspace lane turns a directory into a
     * {@code zolt.lock} path; every core operation is handed the answer.
     */
    public Path lockfilePath() {
        return ProjectLockfile.in(root);
    }

    /**
     * The build context for one member: its own directory for manifest, sources, and outputs, and this
     * workspace's authoritative lockfile for dependency identity. A member-local {@code zolt.lock} is
     * never consulted, so planting one changes nothing a build observes.
     */
    public ProjectBuildContext memberContext(WorkspaceMember member) {
        return ProjectBuildContext.member(member.directory(), lockfilePath(), member.path());
    }

    private static List<String> memberPaths(List<WorkspaceMember> members) {
        return members.stream()
                .map(WorkspaceMember::path)
                .toList();
    }
}
