package sh.zolt.quality;

import sh.zolt.lockfile.ProjectLockfile;
import static sh.zolt.quality.QualityCheckService.CACHE_INTEGRITY;
import static sh.zolt.quality.QualityCheckService.LOCKFILE;

import sh.zolt.cache.ArtifactCacheException;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.maven.repository.RepositoryAccessException;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class LockfileQualityCheck {
    private final ResolveService resolveService;
    private final WorkspaceResolveService workspaceResolveService;
    private final ZoltLockfileReader lockfileReader;

    LockfileQualityCheck(
            ResolveService resolveService,
            WorkspaceResolveService workspaceResolveService,
            ZoltLockfileReader lockfileReader) {
        this.resolveService = resolveService;
        this.workspaceResolveService = workspaceResolveService;
        this.lockfileReader = lockfileReader;
    }

    QualityCheckResult checkProjectLockfile(QualityCheckRequest request, ProjectConfig config) {
        Path lockfile = ProjectLockfile.in(request.projectRoot());
        boolean requireOfflineReady = request.context() == QualityCheckContext.CI && request.requireOfflineReady();
        boolean offline = request.offline() || requireOfflineReady;
        if (!Files.isRegularFile(lockfile)) {
            return QualityCheckResult.failed(
                    LOCKFILE,
                    Optional.empty(),
                    "zolt.lock",
                    "zolt.lock is missing.",
                    "Run `zolt resolve`.");
        }
        try {
            resolveService.resolve(request.projectRoot(), config, request.cacheRoot(), true, offline);
            return QualityCheckResult.passed(
                    LOCKFILE,
                    Optional.empty(),
                    "zolt.lock",
                    requireOfflineReady
                            ? "zolt.lock matches zolt.toml and locked artifacts are available from the local cache."
                            : "zolt.lock matches zolt.toml.");
        } catch (ResolveException exception) {
            return QualityCheckResult.failed(
                    LOCKFILE,
                    Optional.empty(),
                    "zolt.lock",
                    exception.getMessage(),
                    "Run `zolt resolve`.");
        } catch (ArtifactCacheException exception) {
            return QualityCheckResult.failed(
                    LOCKFILE,
                    Optional.empty(),
                    "zolt.lock",
                    exception.getMessage(),
                    requireOfflineReady
                            ? "Run `zolt resolve` to seed the cache, then retry `zolt check --context ci --require-offline-ready`."
                            : "Run `zolt resolve` without --offline to seed the cache, then retry `zolt check --check lockfile --offline`.");
        } catch (RepositoryAccessException exception) {
            return repositoryAccessFailure(exception, false);
        }
    }

    QualityCheckResult checkProjectCacheIntegrity(QualityCheckRequest request) {
        return checkCacheIntegrity(
                Optional.empty(),
                ProjectLockfile.in(request.projectRoot()),
                request.cacheRoot(),
                false);
    }

    QualityCheckResult checkWorkspaceCacheIntegrity(QualityCheckRequest request, Workspace workspace) {
        return checkCacheIntegrity(
                Optional.empty(),
                ProjectLockfile.in(workspace.root()),
                request.cacheRoot(),
                true);
    }

    QualityCheckResult checkWorkspaceLockfile(QualityCheckRequest request, Workspace workspace) {
        Path lockfile = ProjectLockfile.in(workspace.root());
        boolean requireOfflineReady = request.context() == QualityCheckContext.CI && request.requireOfflineReady();
        boolean offline = request.offline() || requireOfflineReady;
        if (!Files.isRegularFile(lockfile)) {
            return QualityCheckResult.failed(
                    LOCKFILE,
                    Optional.empty(),
                    "zolt.lock",
                    "Workspace zolt.lock is missing.",
                    "Run `zolt resolve --workspace`.");
        }
        try {
            workspaceResolveService.resolve(workspace.root(), request.cacheRoot(), true, offline);
            return QualityCheckResult.passed(
                    LOCKFILE,
                    Optional.empty(),
                    "zolt.lock",
                    requireOfflineReady
                            ? "Workspace zolt.lock matches the workspace config and member zolt.toml files, and locked artifacts are available from the local cache."
                            : "Workspace zolt.lock matches the workspace config and member zolt.toml files.");
        } catch (ResolveException exception) {
            return QualityCheckResult.failed(
                    LOCKFILE,
                    Optional.empty(),
                    "zolt.lock",
                    exception.getMessage(),
                    "Run `zolt resolve --workspace`.");
        } catch (ArtifactCacheException exception) {
            return QualityCheckResult.failed(
                    LOCKFILE,
                    Optional.empty(),
                    "zolt.lock",
                    exception.getMessage(),
                    requireOfflineReady
                            ? "Run `zolt resolve --workspace` to seed the cache, then retry `zolt check --workspace --context ci --require-offline-ready`."
                            : "Run `zolt resolve --workspace` without --offline to seed the cache, then retry `zolt check --workspace --check lockfile --offline`.");
        } catch (RepositoryAccessException exception) {
            return repositoryAccessFailure(exception, true);
        }
    }

    private static QualityCheckResult repositoryAccessFailure(
            RepositoryAccessException exception,
            boolean workspace) {
        return QualityCheckResult.failed(
                LOCKFILE,
                Optional.empty(),
                "zolt.lock",
                exception.getMessage(),
                workspace
                        ? "Fix repository access, then run `zolt resolve --workspace`."
                        : "Fix repository access, then run `zolt resolve`.");
    }

    private QualityCheckResult checkCacheIntegrity(
            Optional<String> member,
            Path lockfilePath,
            Path cacheRoot,
            boolean workspaceLockfile) {
        if (!Files.isRegularFile(lockfilePath)) {
            return QualityCheckResult.failed(
                    CACHE_INTEGRITY,
                    member,
                    "zolt.lock",
                    "zolt.lock is missing.",
                    workspaceLockfile ? "Run `zolt resolve --workspace`." : "Run `zolt resolve`.");
        }
        ZoltLockfile lockfile;
        try {
            lockfile = lockfileReader.read(lockfilePath);
        } catch (LockfileReadException exception) {
            return QualityCheckResult.failed(
                    CACHE_INTEGRITY,
                    member,
                    "zolt.lock",
                    exception.getMessage(),
                    workspaceLockfile
                            ? "Run `zolt resolve --workspace` to regenerate zolt.lock."
                            : "Run `zolt resolve` to regenerate zolt.lock.");
        }
        try {
            LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot);
            return QualityCheckResult.passed(
                    CACHE_INTEGRITY,
                    member,
                    "zolt.lock",
                    "All cached artifacts with lockfile checksums match local bytes.");
        } catch (LockfileReadException exception) {
            return QualityCheckResult.failed(
                    CACHE_INTEGRITY,
                    member,
                    "zolt.lock",
                    exception.getMessage(),
                    workspaceLockfile
                            ? "Remove the cache entry or run `zolt resolve --workspace`."
                            : "Remove the cache entry or run `zolt resolve`.");
        }
    }
}
