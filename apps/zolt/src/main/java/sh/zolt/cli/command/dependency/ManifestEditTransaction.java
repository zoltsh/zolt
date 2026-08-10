package sh.zolt.cli.command.dependency;

import sh.zolt.error.ActionableError;
import sh.zolt.lockfile.toml.AtomicLockfileWriter;
import sh.zolt.lockfile.toml.AtomicLockfileWriter.FileSnapshot;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltManifestDocument;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Failure-safe manifest and lockfile edit transaction shared by every public mutation command. */
final class ManifestEditTransaction {
    private static final int LOCK_ROOT_CONFIRMATION_ATTEMPTS = 3;

    private ManifestEditTransaction() {
    }

    static Result execute(
            Path projectRoot,
            Path cacheRoot,
            boolean noResolve,
            ZoltTomlParser parser,
            ZoltTomlWriter writer,
            ResolveService resolveService,
            UnaryOperator<ProjectConfig> mutation) {
        return execute(
                projectRoot,
                cacheRoot,
                noResolve,
                parser,
                writer,
                resolveService,
                mutation,
                () -> {});
    }

    /** Test seam for a deterministic writer arriving after staging but before the lockfile CAS. */
    static Result execute(
            Path projectRoot,
            Path cacheRoot,
            boolean noResolve,
            ZoltTomlParser parser,
            ZoltTomlWriter writer,
            ResolveService resolveService,
            UnaryOperator<ProjectConfig> mutation,
            Runnable beforeLockfileCompareAndSet) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(mutation, "mutation");
        Objects.requireNonNull(beforeLockfileCompareAndSet, "beforeLockfileCompareAndSet");
        return withMutationLock(projectRoot, lockRoot -> executeLocked(
                projectRoot,
                lockRoot,
                cacheRoot,
                noResolve,
                parser,
                writer,
                resolveService,
                mutation,
                beforeLockfileCompareAndSet));
    }

    /** Recovers pending edits and performs a read-only decision under the mutation lock. */
    static <T> T inspect(
            Path projectRoot,
            ZoltTomlParser parser,
            Function<ProjectConfig, T> inspection) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(inspection, "inspection");
        return withMutationLock(projectRoot, lockRoot -> {
            ManifestEditRecovery.recoverAll(projectRoot, lockRoot);
            ManifestMutationScope scope = ManifestMutationScope.discover(projectRoot, lockRoot);
            return inspection.apply(parser.parse(scope.manifestPath()));
        });
    }

    private static <T> T withMutationLock(Path projectRoot, Function<Path, T> action) {
        WorkspaceDiscoveryService discovery = new WorkspaceDiscoveryService();
        Path standaloneRoot = projectRoot.toAbsolutePath().normalize();
        for (int attempt = 0; attempt < LOCK_ROOT_CONFIRMATION_ATTEMPTS; attempt++) {
            Path lockRoot = discovery.discoverRoot(projectRoot)
                    .map(path -> path.toAbsolutePath().normalize())
                    .orElse(standaloneRoot);
            try (WorkspaceMutationLock ignored = WorkspaceMutationLock.acquire(lockRoot)) {
                Path confirmedRoot = discovery.discoverRoot(projectRoot)
                        .map(path -> path.toAbsolutePath().normalize())
                        .orElse(standaloneRoot);
                if (lockRoot.equals(confirmedRoot)) {
                    return action.apply(lockRoot);
                }
            }
        }
        throw new ZoltConfigException(
                "Workspace root changed repeatedly while acquiring the manifest mutation lock. Retry the command.");
    }

    private static Result executeLocked(
            Path projectRoot,
            Path lockRoot,
            Path cacheRoot,
            boolean noResolve,
            ZoltTomlParser parser,
            ZoltTomlWriter writer,
            ResolveService resolveService,
            UnaryOperator<ProjectConfig> mutation,
            Runnable beforeLockfileCompareAndSet) {
        ManifestEditRecovery.recoverAll(projectRoot, lockRoot);
        ManifestMutationScope scope = ManifestMutationScope.discover(projectRoot, lockRoot);
        ZoltManifestDocument original = parser.parseDocument(scope.manifestPath());
        ProjectConfig requested = mutation.apply(original.config());
        ZoltManifestDocument edited = writer.patchDocument(original, requested);
        if (edited.source().equals(original.source())) {
            return new Result(original.config(), edited.config(), null);
        }
        if (noResolve) {
            writer.writePrepared(scope.manifestPath(), original, edited);
            return new Result(original.config(), edited.config(), null);
        }
        if (resolveService == null) {
            throw new IllegalStateException("Resolve service is required for a resolving manifest edit.");
        }
        return resolveAndCommit(
                scope,
                cacheRoot,
                writer,
                resolveService,
                original,
                edited,
                beforeLockfileCompareAndSet);
    }

    private static Result resolveAndCommit(
            ManifestMutationScope scope,
            Path cacheRoot,
            ZoltTomlWriter writer,
            ResolveService resolveService,
            ZoltManifestDocument original,
            ZoltManifestDocument edited,
            Runnable beforeLockfileCompareAndSet) {
        Path transaction = scope.transactionDirectory();
        Path manifestBackup = transaction.resolve("zolt.toml.backup");
        Path manifestStaged = transaction.resolve("zolt.toml.staged");
        Path lockfileBackup = transaction.resolve("zolt.lock.backup");
        Path lockfileAbsent = transaction.resolve("zolt.lock.absent");
        Path resolveDirectory = transaction.resolve("resolve");
        try {
            Files.createDirectories(resolveDirectory);
            Files.writeString(manifestBackup, original.source());
            Files.writeString(manifestStaged, edited.source());
            FileSnapshot originalLockfile = AtomicLockfileWriter.capture(scope.lockfilePath());
            if (originalLockfile.exists()) {
                Files.writeString(lockfileBackup, originalLockfile.content());
                Files.writeString(resolveDirectory.resolve("zolt.lock"), originalLockfile.content());
            } else {
                Files.writeString(lockfileAbsent, "absent\n");
            }
            ManifestEditRecovery.writeState(transaction, ManifestEditRecovery.STAGING);

            ResolveResult staged = resolveStaged(scope, resolveDirectory, cacheRoot, resolveService, edited);
            String resolvedLockfile = Files.readString(resolveDirectory.resolve("zolt.lock"));
            Files.writeString(transaction.resolve("zolt.lock.staged"), resolvedLockfile);
            requireManifestUnchanged(scope.manifestPath(), original.source());
            ManifestEditRecovery.writeState(transaction, ManifestEditRecovery.PREPARED);

            beforeLockfileCompareAndSet.run();
            try {
                AtomicLockfileWriter.compareAndSetAtomically(
                        scope.lockfilePath(),
                        originalLockfile,
                        FileSnapshot.present(resolvedLockfile),
                        () -> {
                            writer.writePrepared(scope.manifestPath(), original, edited);
                            ManifestEditRecovery.writeState(
                                    transaction,
                                    ManifestEditRecovery.MANIFEST_COMMITTED);
                        });
            } catch (AtomicLockfileWriter.ConcurrentWriteException exception) {
                ManifestEditRecovery.deleteRecursively(transaction);
                throw new ZoltConfigException(ActionableError.of(
                        "zolt.lock changed while dependency resolution was in progress. No changes were written.",
                        "Retry the command against the current lockfile.",
                        exception));
            }
            try {
                ManifestEditRecovery.writeState(transaction, ManifestEditRecovery.COMMITTED);
                ManifestEditRecovery.deleteRecursively(transaction);
            } catch (IOException ignored) {
                // Both files are committed. Recovery recognizes staged/staged even if the final
                // state marker or cleanup could not be persisted.
            }
            ResolveResult committed = new ResolveResult(
                    staged.resolvedCount(),
                    staged.downloadCount(),
                    staged.conflictCount(),
                    scope.lockfilePath(),
                    staged.metrics());
            return new Result(original.config(), edited.config(), committed);
        } catch (IOException exception) {
            if (!ManifestEditRecovery.rollbackAfterFailure(
                    transaction, scope.manifestRoot(), scope.lockRoot(), exception)) {
                throw ManifestEditRecovery.recoveryFailure(transaction, exception);
            }
            throw new ZoltConfigException(
                    "Could not commit the zolt.toml and zolt.lock edit transaction. Both files were restored.");
        } catch (RuntimeException exception) {
            if (!ManifestEditRecovery.rollbackAfterFailure(
                    transaction, scope.manifestRoot(), scope.lockRoot(), exception)) {
                throw ManifestEditRecovery.recoveryFailure(transaction, exception);
            }
            throw exception;
        }
    }

    private static ResolveResult resolveStaged(
            ManifestMutationScope scope,
            Path resolveDirectory,
            Path cacheRoot,
            ResolveService resolveService,
            ZoltManifestDocument edited) throws IOException {
        if (scope.workspace() == null) {
            return resolveService.resolve(resolveDirectory, edited.config(), cacheRoot);
        }
        prepareShadowWorkspace(scope, resolveDirectory, edited.source());
        return new WorkspaceResolveService(resolveService).resolve(
                resolveDirectory,
                cacheRoot,
                false,
                false,
                "retry the manifest edit");
    }

    private static void prepareShadowWorkspace(
            ManifestMutationScope scope,
            Path resolveDirectory,
            String editedManifest) throws IOException {
        Workspace workspace = scope.workspace();
        Path workspaceConfig = workspace.configPath().toAbsolutePath().normalize();
        Path shadowConfig = resolveDirectory.resolve(workspace.root().relativize(workspaceConfig));
        writeShadowInput(scope, workspaceConfig, shadowConfig, editedManifest);
        for (WorkspaceMember member : workspace.members()) {
            Path source = member.directory().resolve("zolt.toml").toAbsolutePath().normalize();
            Path destination = resolveDirectory.resolve(member.path()).normalize().resolve("zolt.toml");
            writeShadowInput(scope, source, destination, editedManifest);
        }
    }

    private static void writeShadowInput(
            ManifestMutationScope scope,
            Path source,
            Path destination,
            String editedManifest) throws IOException {
        String content = source.equals(scope.manifestPath())
                ? editedManifest
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

    static void recover(Path transaction, Path projectRoot) {
        ManifestEditRecovery.recover(transaction, projectRoot);
    }

    private static void requireManifestUnchanged(Path manifestPath, String expected) throws IOException {
        if (!Files.readString(manifestPath).equals(expected)) {
            throw new ZoltConfigException(
                    "zolt.toml changed while dependency resolution was in progress. No changes were written; retry against the current manifest.");
        }
    }

    record Result(ProjectConfig original, ProjectConfig updated, ResolveResult resolveResult) {
        boolean changed() {
            return !original.equals(updated);
        }
    }

}
