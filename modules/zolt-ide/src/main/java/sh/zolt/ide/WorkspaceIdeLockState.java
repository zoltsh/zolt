package sh.zolt.ide;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import sh.zolt.cache.ArtifactCacheException;
import sh.zolt.error.ActionableException;
import sh.zolt.lockfile.ContentAddressedLockCapability;
import sh.zolt.lockfile.ProjectLockfile;
import sh.zolt.lockfile.WorkspaceGraphLockCapability;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.resolve.ResolveException;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import sh.zolt.workspace.service.Workspace;

/**
 * Reads the ONE lock an IDE export may consume for a workspace — the root's — and turns every way it
 * can be unusable into a diagnostic rather than an exception.
 *
 * <p>An IDE model is a report, so a missing, unreadable, or too-old lock is described in the model
 * instead of failing the command; the IDE still gets source roots, output paths, and configuration
 * while the classpath is reported as unavailable with the exact command that fixes it.
 *
 * <p>Shared by both exports: {@code --workspace} reads it once for the whole family, and a
 * member-directory export reads the same state for its one member, so a member's lock diagnostics can
 * never differ from the workspace's account of the same file.
 */
final class WorkspaceIdeLockState {
    private final ZoltLockfileReader lockfileReader;
    private final WorkspaceResolveService workspaceResolveService;

    WorkspaceIdeLockState(
            ZoltLockfileReader lockfileReader,
            WorkspaceResolveService workspaceResolveService) {
        this.lockfileReader = lockfileReader;
        this.workspaceResolveService = workspaceResolveService;
    }

    WorkspaceLockState read(
            Workspace workspace,
            Path cacheRoot,
            boolean checkLock,
            boolean offline) {
        Path lockfilePath = ProjectLockfile.in(workspace.root()).normalize();
        if (!Files.exists(lockfilePath)) {
            return new WorkspaceLockState(
                    null,
                    List.of(new IdeModel.Diagnostic(
                            "error",
                            "LOCKFILE_MISSING",
                            "Could not find workspace zolt.lock.",
                            lockfilePath,
                            "Run zolt resolve --workspace.")));
        }

        try {
            ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
            if (!WorkspaceGraphLockCapability.supportsMemberGraphEvidence(lockfile)) {
                return new WorkspaceLockState(
                        null,
                        List.of(new IdeModel.Diagnostic(
                                "error",
                                "LOCKFILE_GRAPH_SCHEMA_OUTDATED",
                                "Workspace zolt.lock version "
                                        + lockfile.version()
                                        + " lacks version "
                                        + WorkspaceGraphLockCapability.MINIMUM_VERSION
                                        + " member-qualified optional-boundary evidence.",
                                lockfilePath,
                                "Run zolt resolve --workspace.")));
            }
            List<IdeModel.Diagnostic> diagnostics = new ArrayList<>();
            if (checkLock && !ContentAddressedLockCapability.supportsArtifactCachePaths(lockfile)) {
                diagnostics.add(new IdeModel.Diagnostic(
                        "error",
                        "LOCKFILE_MIGRATION_REQUIRED",
                        "Workspace zolt.lock version "
                                + lockfile.version()
                                + " predates the version "
                                + ContentAddressedLockCapability.MINIMUM_VERSION
                                + " content-addressed artifact cache path contract required by this Zolt.",
                        lockfilePath,
                        "Run zolt resolve --workspace."));
                return new WorkspaceLockState(lockfile, diagnostics);
            }
            if (checkLock) {
                checkFreshness(workspace, cacheRoot, offline, lockfilePath, diagnostics);
            }
            return new WorkspaceLockState(lockfile, diagnostics);
        } catch (LockfileReadException exception) {
            return new WorkspaceLockState(
                    null,
                    List.of(new IdeModel.Diagnostic(
                            "error",
                            "LOCKFILE_UNREADABLE",
                            exception.getMessage(),
                            lockfilePath,
                            "Run zolt resolve --workspace.")));
        }
    }

    private void checkFreshness(
            Workspace workspace,
            Path cacheRoot,
            boolean offline,
            Path lockfilePath,
            List<IdeModel.Diagnostic> diagnostics) {
        try {
            workspaceResolveService.resolve(workspace.root(), cacheRoot, true, offline);
        } catch (ResolveException exception) {
            diagnostics.add(new IdeModel.Diagnostic(
                    "error",
                    lockDiagnosticCode(exception),
                    exception.getMessage(),
                    lockfilePath,
                    "Run zolt resolve --workspace."));
        } catch (ArtifactCacheException exception) {
            diagnostics.add(new IdeModel.Diagnostic(
                    "error",
                    "LOCKFILE_CHECK_UNAVAILABLE",
                    exception.getMessage(),
                    lockfilePath,
                    "Run zolt resolve --workspace without --offline to seed the cache, then retry zolt ide model --workspace --offline."));
        } catch (ActionableException exception) {
            diagnostics.add(new IdeModel.Diagnostic(
                    "error",
                    "LOCKFILE_MIGRATION_REQUIRED",
                    exception.getMessage(),
                    lockfilePath,
                    "Run zolt resolve --workspace."));
        }
    }

    private static String lockDiagnosticCode(ResolveException exception) {
        return exception.getMessage().contains("out of date")
                ? "LOCKFILE_STALE"
                : "LOCKFILE_CHECK_FAILED";
    }


    /** The workspace lock an export ran against, and everything wrong with it. */
    record WorkspaceLockState(ZoltLockfile lockfile, List<IdeModel.Diagnostic> diagnostics) {
        WorkspaceLockState {
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
