package sh.zolt.workspace.service;

/** Exact config and lockfile bytes used to create a workspace plan. */
final class WorkspacePlanInputSnapshot {
    private final WorkspaceInputs inputs;

    private WorkspacePlanInputSnapshot(WorkspaceInputs inputs) {
        this.inputs = inputs;
    }

    static WorkspacePlanInputSnapshot capture(Workspace workspace) {
        return new WorkspacePlanInputSnapshot(workspace.inputs());
    }

    static WorkspacePlanInputSnapshot unchecked() {
        return new WorkspacePlanInputSnapshot(WorkspaceInputs.unchecked());
    }

    void requireCurrent() {
        inputs.requireCurrent();
    }
}
