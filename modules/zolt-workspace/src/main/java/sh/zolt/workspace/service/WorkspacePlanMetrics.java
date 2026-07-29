package sh.zolt.workspace.service;

public record WorkspacePlanMetrics(
        long discoveryNanos,
        long selectionNanos,
        long resolutionNanos,
        long lockfileReadNanos,
        int workspaceMembers,
        int workspaceEdges,
        int lockfilePackages) {
    public WorkspacePlanMetrics {
        discoveryNanos = Math.max(0L, discoveryNanos);
        selectionNanos = Math.max(0L, selectionNanos);
        resolutionNanos = Math.max(0L, resolutionNanos);
        lockfileReadNanos = Math.max(0L, lockfileReadNanos);
        workspaceMembers = Math.max(0, workspaceMembers);
        workspaceEdges = Math.max(0, workspaceEdges);
        lockfilePackages = Math.max(0, lockfilePackages);
    }

    public static WorkspacePlanMetrics empty() {
        return new WorkspacePlanMetrics(0L, 0L, 0L, 0L, 0, 0, 0);
    }
}
