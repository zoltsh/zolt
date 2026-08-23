package sh.zolt.workspace.service;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.lockfile.WorkspaceGraphLockCapability;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/** Creates a workspace plan from one authoritative config and lockfile snapshot. */
final class WorkspaceBuildPlanner {
    private final Function<Path, Optional<Workspace>> discovery;
    private final WorkspaceResolveService resolve;
    private final ZoltLockfileReader lockfiles;
    private final WorkspaceMemberSelector members;

    WorkspaceBuildPlanner(ResolveService resolveService) {
        this(
                new ManifestWorkspaceLoader(),
                new WorkspaceResolveService(resolveService),
                new ZoltLockfileReader(),
                new WorkspaceMemberSelector());
    }

    WorkspaceBuildPlanner(
            ManifestWorkspaceLoader discovery,
            WorkspaceResolveService resolve,
            ZoltLockfileReader lockfiles,
            WorkspaceMemberSelector members) {
        this(discovery::discover, resolve, lockfiles, members);
    }

    WorkspaceBuildPlanner(
            Function<Path, Optional<Workspace>> discovery,
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
        Workspace workspace = discovery.apply(start).orElseThrow(() -> ResolveException.actionable(
                "Could not find workspace config.",
                "Run `zolt build --workspace` from a workspace directory or add zolt.toml with [workspace]."));
        return plan(
                workspace,
                elapsedSince(discoveryStarted),
                cacheRoot,
                offline,
                selectionRequest,
                includeTestLanes,
                new VerifiedArtifactIndex());
    }

    /**
     * Plans against a workspace the caller already discovered, which is how a command that gated on
     * lock freshness avoids reading and parsing every member config a second time. The caller passes
     * what that discovery cost, since discovery still happened and its counter must still report it.
     */
    WorkspaceBuildPlan plan(
            Workspace discovered,
            long discoveryNanos,
            Path cacheRoot,
            boolean offline,
            WorkspaceSelectionRequest selectionRequest,
            boolean includeTestLanes,
            VerifiedArtifactIndex artifactIndex) {
        Workspace workspace = discovered;
        long selectionStarted = System.nanoTime();
        WorkspaceSelection selection = includeTestLanes
                ? members.select(workspace, selectionRequest)
                : members.selectMain(workspace, selectionRequest);
        long selectionNanos = elapsedSince(selectionStarted);
        Path lockfilePath = workspace.lockfilePath();
        Optional<ResolveResult> resolveResult = Optional.empty();
        long resolutionNanos = 0L;
        if (!Files.isRegularFile(lockfilePath)) {
            long resolutionStarted = System.nanoTime();
            resolveResult = Optional.of(resolve.resolve(
                    workspace,
                    cacheRoot,
                    false,
                    offline,
                    "zolt build --workspace"));
            resolutionNanos = elapsedSince(resolutionStarted);
            workspace.inputs().requireCurrent();
        }

        long lockfileReadStarted = System.nanoTime();
        byte[] lockfileBytes = readLockfile(lockfilePath);
        String lockfileContent = new String(lockfileBytes, StandardCharsets.UTF_8);
        workspace = workspace.withInputs(
                workspace.inputs().withContent(lockfilePath, lockfileBytes));
        ZoltLockfile lockfile = lockfiles.read(lockfileContent);
        WorkspaceGraphLockCapability.requireMemberGraphEvidence(lockfile);
        WorkspacePlanInputSnapshot inputSnapshot =
                WorkspacePlanInputSnapshot.capture(workspace);
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
                        cacheRoot,
                        artifactIndex),
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

    private static byte[] readLockfile(Path path) {
        try {
            return Files.readAllBytes(path);
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
