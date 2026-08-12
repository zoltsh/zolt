package sh.zolt.cli.command.dependency;

import sh.zolt.error.ActionableError;
import sh.zolt.lockfile.toml.AtomicLockfileWriter;
import sh.zolt.lockfile.toml.AtomicLockfileWriter.FileSnapshot;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Commits one prepared manifest source and its regenerated lock under a shared journal. */
final class ManifestEditCommitter {
    private ManifestEditCommitter() {
    }

    static ManifestCommitResult commit(
            ManifestMutationScope scope,
            Path cacheRoot,
            boolean noResolve,
            ResolveService resolveService,
            ProjectConfig standaloneConfig,
            String originalSource,
            String editedSource,
            SourceWriter writer,
            Runnable beforeLockfileCompareAndSet) {
        if (editedSource.equals(originalSource)) {
            return result(scope, null, false, false);
        }
        if (noResolve) {
            if (scope.workspace() != null) {
                scope.workspace().inputs().requireCurrent();
            }
            writer.write(scope.manifestPath(), originalSource, editedSource);
            return result(scope, null, true, false);
        }
        if (resolveService == null) {
            throw new IllegalStateException("Resolve service is required for a resolving manifest edit.");
        }
        return resolveAndCommit(
                scope,
                cacheRoot,
                resolveService,
                standaloneConfig,
                originalSource,
                editedSource,
                writer,
                beforeLockfileCompareAndSet);
    }

    private static ManifestCommitResult resolveAndCommit(
            ManifestMutationScope scope,
            Path cacheRoot,
            ResolveService resolveService,
            ProjectConfig standaloneConfig,
            String originalSource,
            String editedSource,
            SourceWriter writer,
            Runnable beforeLockfileCompareAndSet) {
        Path transaction = scope.transactionDirectory();
        Path lockfileBackup = transaction.resolve("zolt.lock.backup");
        Path resolveDirectory = transaction.resolve("resolve");
        try {
            Files.createDirectories(resolveDirectory);
            ManifestEditRecovery.writeScope(transaction, scope);
            Files.writeString(transaction.resolve("zolt.toml.backup"), originalSource);
            Files.writeString(transaction.resolve("zolt.toml.staged"), editedSource);
            FileSnapshot originalLockfile = AtomicLockfileWriter.capture(scope.lockfilePath());
            if (originalLockfile.exists()) {
                Files.writeString(lockfileBackup, originalLockfile.content());
                Files.writeString(resolveDirectory.resolve("zolt.lock"), originalLockfile.content());
            } else {
                Files.writeString(transaction.resolve("zolt.lock.absent"), "absent\n");
            }
            ManifestEditRecovery.writeState(transaction, ManifestEditRecovery.STAGING);

            ResolveResult staged = resolveStaged(
                    scope, resolveDirectory, cacheRoot, resolveService, standaloneConfig, editedSource);
            String resolvedLockfile = Files.readString(resolveDirectory.resolve("zolt.lock"));
            Files.writeString(transaction.resolve("zolt.lock.staged"), resolvedLockfile);
            requireManifestUnchanged(scope.manifestPath(), originalSource);
            ManifestEditRecovery.writeState(transaction, ManifestEditRecovery.PREPARED);

            beforeLockfileCompareAndSet.run();
            if (scope.workspace() != null) {
                scope.workspace().inputs().requireCurrent();
            }
            try {
                AtomicLockfileWriter.compareAndSetAtomically(
                        scope.lockfilePath(),
                        originalLockfile,
                        FileSnapshot.present(resolvedLockfile),
                        () -> {
                            writer.write(scope.manifestPath(), originalSource, editedSource);
                            ManifestEditRecovery.writeState(
                                    transaction, ManifestEditRecovery.MANIFEST_COMMITTED);
                        });
            } catch (AtomicLockfileWriter.ConcurrentWriteException exception) {
                ManifestEditRecovery.deleteRecursively(transaction);
                throw new ZoltConfigException(ActionableError.of(
                        "zolt.lock changed while dependency resolution was in progress. No changes were written.",
                        "Retry the command against the current lockfile.",
                        exception));
            }
            finish(transaction);
            ResolveResult committed = new ResolveResult(
                    staged.resolvedCount(),
                    staged.downloadCount(),
                    staged.conflictCount(),
                    scope.lockfilePath(),
                    staged.metrics());
            boolean lockChanged = !originalLockfile.equals(FileSnapshot.present(resolvedLockfile));
            return result(scope, committed, true, lockChanged);
        } catch (IOException exception) {
            rollback(scope, exception);
            throw new ZoltConfigException(
                    "Could not commit the dependency manifest and zolt.lock edit transaction. Both files were restored.");
        } catch (RuntimeException exception) {
            rollback(scope, exception);
            throw exception;
        }
    }

    private static ResolveResult resolveStaged(
            ManifestMutationScope scope,
            Path resolveDirectory,
            Path cacheRoot,
            ResolveService resolveService,
            ProjectConfig standaloneConfig,
            String editedSource) throws IOException {
        if (scope.workspace() == null) {
            if (standaloneConfig == null) {
                throw new IllegalStateException("Standalone manifest edits require a project configuration.");
            }
            return resolveService.resolve(resolveDirectory, standaloneConfig, cacheRoot);
        }
        prepareShadowWorkspace(scope, resolveDirectory, editedSource);
        return new WorkspaceResolveService(resolveService).resolve(
                resolveDirectory, cacheRoot, false, false, "retry the manifest edit");
    }

    private static void prepareShadowWorkspace(
            ManifestMutationScope scope,
            Path resolveDirectory,
            String editedSource) throws IOException {
        Workspace workspace = scope.workspace();
        Path workspaceConfig = workspace.configPath().toAbsolutePath().normalize();
        Path shadowConfig = resolveDirectory.resolve(workspace.root().relativize(workspaceConfig));
        writeShadowInput(scope, workspaceConfig, shadowConfig, editedSource);
        for (WorkspaceMember member : workspace.members()) {
            Path source = member.directory().resolve("zolt.toml").toAbsolutePath().normalize();
            Path destination = resolveDirectory.resolve(member.path()).normalize().resolve("zolt.toml");
            writeShadowInput(scope, source, destination, editedSource);
        }
    }

    private static void writeShadowInput(
            ManifestMutationScope scope,
            Path source,
            Path destination,
            String editedSource) throws IOException {
        String content = source.equals(scope.manifestPath())
                ? editedSource
                : scope.workspace().inputs().content(source).orElseGet(() -> readUnchecked(source));
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, content);
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new ZoltConfigException(ActionableError.of(
                    "Could not stage workspace input " + path + ".",
                    "Check that the workspace manifest is readable, then retry the edit.",
                    exception));
        }
    }

    private static void requireManifestUnchanged(Path manifestPath, String expected) throws IOException {
        if (!Files.readString(manifestPath).equals(expected)) {
            throw new ZoltConfigException(
                    "The dependency manifest changed while resolution was in progress. No changes were written; retry against the current manifest.");
        }
    }

    private static void finish(Path transaction) throws IOException {
        try {
            ManifestEditRecovery.writeState(transaction, ManifestEditRecovery.COMMITTED);
            ManifestEditRecovery.deleteRecursively(transaction);
        } catch (IOException ignored) {
            // Both files are committed. Recovery recognizes staged/staged if cleanup was interrupted.
        }
    }

    private static void rollback(ManifestMutationScope scope, Exception failure) {
        if (!ManifestEditRecovery.rollbackAfterFailure(
                scope.transactionDirectory(), scope.manifestRoot(), scope.lockRoot(), failure)) {
            throw ManifestEditRecovery.recoveryFailure(scope.transactionDirectory(), failure);
        }
    }

    private static ManifestCommitResult result(
            ManifestMutationScope scope,
            ResolveResult resolveResult,
            boolean manifestChanged,
            boolean lockfileChanged) {
        return new ManifestCommitResult(
                resolveResult, scope.manifestPath(), scope.lockfilePath(), manifestChanged, lockfileChanged);
    }

    @FunctionalInterface
    interface SourceWriter {
        void write(Path path, String original, String edited);
    }
}
