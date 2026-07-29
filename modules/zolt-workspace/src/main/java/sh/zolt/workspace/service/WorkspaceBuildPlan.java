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
                WorkspacePlanMetrics.empty());
    }

    public WorkspaceBuildPlan {
        resolveResult = resolveResult == null ? Optional.empty() : resolveResult;
        if (executionContext == null) {
            throw new IllegalArgumentException("Workspace execution context is required.");
        }
        metrics = metrics == null ? WorkspacePlanMetrics.empty() : metrics;
    }

    public boolean resolvedLockfile() {
        return resolveResult.isPresent();
    }
}
