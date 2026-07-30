package sh.zolt.workspace.service;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.resolve.ResolveResult;
import java.nio.file.Path;
import java.util.Optional;

public record WorkspaceBuildPlan(
        Workspace workspace,
        WorkspaceSelection selection,
        Optional<ResolveResult> resolveResult,
        ZoltLockfile lockfile,
        WorkspaceExecutionContext executionContext,
        WorkspacePlanInputSnapshot inputSnapshot,
        WorkspacePlanMetrics metrics) {
    public WorkspaceBuildPlan(
            Workspace workspace,
            WorkspaceSelection selection,
            Optional<ResolveResult> resolveResult,
            ZoltLockfile lockfile) {
        this(
                workspace,
                selection,
                resolveResult,
                lockfile,
                new WorkspaceExecutionContext(workspace, lockfile, Path.of(".")),
                WorkspacePlanInputSnapshot.unchecked(),
                WorkspacePlanMetrics.empty());
    }

    public WorkspaceBuildPlan(
            Workspace workspace,
            WorkspaceSelection selection,
            Optional<ResolveResult> resolveResult,
            ZoltLockfile lockfile,
            WorkspaceExecutionContext executionContext) {
        this(
                workspace,
                selection,
                resolveResult,
                lockfile,
                executionContext,
                WorkspacePlanInputSnapshot.unchecked(),
                WorkspacePlanMetrics.empty());
    }

    public WorkspaceBuildPlan(
            Workspace workspace,
            WorkspaceSelection selection,
            Optional<ResolveResult> resolveResult,
            ZoltLockfile lockfile,
            WorkspaceExecutionContext executionContext,
            WorkspacePlanMetrics metrics) {
        this(
                workspace,
                selection,
                resolveResult,
                lockfile,
                executionContext,
                WorkspacePlanInputSnapshot.unchecked(),
                metrics);
    }

    public WorkspaceBuildPlan {
        resolveResult = resolveResult == null ? Optional.empty() : resolveResult;
        if (executionContext == null) {
            throw new IllegalArgumentException("Workspace execution context is required.");
        }
        inputSnapshot = inputSnapshot == null
                ? WorkspacePlanInputSnapshot.unchecked()
                : inputSnapshot;
        metrics = metrics == null ? WorkspacePlanMetrics.empty() : metrics;
    }

    public boolean resolvedLockfile() {
        return resolveResult.isPresent();
    }

    public WorkspaceBuildPlan requireInputsCurrent() {
        inputSnapshot.requireCurrent();
        return this;
    }
}
