package sh.zolt.workspace.service;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.WorkspaceGraphLockCapability;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Creates a workspace plan from one authoritative config and lockfile snapshot. */
final class WorkspaceBuildPlanner {
    private final WorkspaceDiscoveryService discovery;
    private final WorkspaceResolveService resolve;
    private final ZoltLockfileReader lockfiles;
    private final WorkspaceMemberSelector members;

    WorkspaceBuildPlanner(ResolveService resolveService) {
        this(
                new WorkspaceDiscoveryService(),
                new WorkspaceResolveService(resolveService),
                new ZoltLockfileReader(),
                new WorkspaceMemberSelector());
    }

    WorkspaceBuildPlanner(
            WorkspaceDiscoveryService discovery,
            WorkspaceResolveService resolve,
            ZoltLockfileReader lockfiles,
            WorkspaceMemberSelector members) {
        this.discovery = discovery;
        this.resolve = resolve;
        this.lockfiles = lockfiles;
        this.members = members;
    }

    WorkspaceBuildPlan plan(
            Path startDirectory,
            Path cacheRoot,
            boolean offline,
            WorkspaceSelectionRequest selectionRequest,
            boolean includeTestLanes) {
        Path start = startDirectory.toAbsolutePath().normalize();
        long discoveryStarted = System.nanoTime();
        Workspace workspace = discovery.discover(start).orElseThrow(() -> ResolveException.actionable(
                "Could not find workspace config.",
                "Run `zolt build --workspace` from a workspace directory or add zolt.toml with [workspace]."));
        long discoveryNanos = elapsedSince(discoveryStarted);
        long selectionStarted = System.nanoTime();
        WorkspaceSelection selection = includeTestLanes
                ? members.select(workspace, selectionRequest)
                : members.selectMain(workspace, selectionRequest);
        long selectionNanos = elapsedSince(selectionStarted);
        Path lockfilePath = workspace.root().resolve("zolt.lock");
        Optional<ResolveResult> resolveResult = Optional.empty();
        long resolutionNanos = 0L;
        if (!Files.isRegularFile(lockfilePath)) {
            long resolutionStarted = System.nanoTime();
            resolveResult = Optional.of(resolve.resolve(
                    start,
                    cacheRoot,
                    false,
                    offline,
                    "zolt build --workspace"));
            resolutionNanos = elapsedSince(resolutionStarted);
        }

        long lockfileReadStarted = System.nanoTime();
        String lockfileContent = readLockfile(lockfilePath);
        ZoltLockfile lockfile = lockfiles.read(lockfileContent);
        WorkspaceGraphLockCapability.requireMemberGraphEvidence(lockfile);
        WorkspacePlanInputSnapshot inputSnapshot =
                WorkspacePlanInputSnapshot.capture(workspace, lockfilePath, lockfileContent);
        inputSnapshot.requireCurrent();
        long lockfileReadNanos = elapsedSince(lockfileReadStarted);
        return new WorkspaceBuildPlan(
                workspace,
                selection,
                resolveResult,
                lockfile,
                new WorkspaceExecutionContext(
                        workspace,
                        lockfile,
                        cacheRoot),
                inputSnapshot,
                new WorkspacePlanMetrics(
                        discoveryNanos,
                        selectionNanos,
                        resolutionNanos,
                        lockfileReadNanos,
                        workspace.members().size(),
                        workspace.edges().size(),
                        lockfile.packages().size()));
    }

    private static String readLockfile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw ResolveException.actionable(
                    "Could not read zolt.lock at " + path + " while planning the workspace.",
                    "Check that the file exists and is readable, then retry the command.");
        }
    }

    private static long elapsedSince(long started) {
        return Math.max(0L, System.nanoTime() - started);
    }
}
