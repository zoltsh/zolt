package sh.zolt.cli.command.dependency;

import sh.zolt.error.ActionableError;
import sh.zolt.lockfile.toml.AtomicLockfileWriter;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltManifestDocument;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Failure-safe manifest and lockfile edit transaction shared by every public mutation command. */
final class ManifestEditTransaction {
    private static final String TRANSACTION_DIRECTORY = "manifest-edit-transaction";
    private static final String STATE = "state";
    private static final String STAGING = "STAGING";
    private static final String PREPARED = "PREPARED";
    private static final String MANIFEST_COMMITTED = "MANIFEST_COMMITTED";
    private static final String COMMITTED = "COMMITTED";
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
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(mutation, "mutation");
        Supplier<Result> action = () -> executeLocked(
                projectRoot,
                cacheRoot,
                noResolve,
                parser,
                writer,
                resolveService,
                mutation);
        return withMutationLock(projectRoot, action);
    }

    private static <T> T withMutationLock(Path projectRoot, Supplier<T> action) {
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
                    return action.get();
                }
            }
        }
        throw new ZoltConfigException(
                "Workspace root changed repeatedly while acquiring the manifest mutation lock. Retry the command.");
    }

    private static Result executeLocked(
            Path projectRoot,
            Path cacheRoot,
            boolean noResolve,
            ZoltTomlParser parser,
            ZoltTomlWriter writer,
            ResolveService resolveService,
            UnaryOperator<ProjectConfig> mutation) {
        Path transaction = transactionDirectory(projectRoot);
        recover(transaction, projectRoot);

        Path manifestPath = projectRoot.resolve("zolt.toml");
        ZoltManifestDocument original = parser.parseDocument(manifestPath);
        ProjectConfig requested = mutation.apply(original.config());
        ZoltManifestDocument edited = writer.patchDocument(original, requested);
        if (noResolve) {
            writer.writePrepared(manifestPath, original, edited);
            return new Result(original.config(), edited.config(), null);
        }
        if (resolveService == null) {
            throw new IllegalStateException("Resolve service is required for a resolving manifest edit.");
        }
        return resolveAndCommit(
                projectRoot,
                cacheRoot,
                writer,
                resolveService,
                transaction,
                manifestPath,
                original,
                edited);
    }

    private static Result resolveAndCommit(
            Path projectRoot,
            Path cacheRoot,
            ZoltTomlWriter writer,
            ResolveService resolveService,
            Path transaction,
            Path manifestPath,
            ZoltManifestDocument original,
            ZoltManifestDocument edited) {
        Path lockfilePath = projectRoot.resolve("zolt.lock");
        Path manifestBackup = transaction.resolve("zolt.toml.backup");
        Path manifestStaged = transaction.resolve("zolt.toml.staged");
        Path lockfileBackup = transaction.resolve("zolt.lock.backup");
        Path lockfileAbsent = transaction.resolve("zolt.lock.absent");
        Path resolveDirectory = transaction.resolve("resolve");
        try {
            Files.createDirectories(resolveDirectory);
            Files.writeString(manifestBackup, original.source());
            Files.writeString(manifestStaged, edited.source());
            String originalLockfile = null;
            if (Files.isRegularFile(lockfilePath)) {
                originalLockfile = Files.readString(lockfilePath);
                Files.writeString(lockfileBackup, originalLockfile);
                Files.writeString(resolveDirectory.resolve("zolt.lock"), originalLockfile);
            } else {
                Files.writeString(lockfileAbsent, "absent\n");
            }
            writeState(transaction, STAGING);

            ResolveResult staged = resolveService.resolve(resolveDirectory, edited.config(), cacheRoot);
            String resolvedLockfile = Files.readString(resolveDirectory.resolve("zolt.lock"));
            Files.writeString(transaction.resolve("zolt.lock.staged"), resolvedLockfile);
            requireManifestUnchanged(manifestPath, original.source());
            requireLockfileUnchanged(lockfilePath, originalLockfile);
            writeState(transaction, PREPARED);

            writer.writePrepared(manifestPath, original, edited);
            writeState(transaction, MANIFEST_COMMITTED);
            AtomicLockfileWriter.writeAtomically(lockfilePath, resolvedLockfile);
            writeState(transaction, COMMITTED);
            try {
                deleteRecursively(transaction);
            } catch (IOException ignored) {
                // The files are committed. A later mutation will see COMMITTED and finish cleanup.
            }
            ResolveResult committed = new ResolveResult(
                    staged.resolvedCount(),
                    staged.downloadCount(),
                    staged.conflictCount(),
                    lockfilePath,
                    staged.metrics());
            return new Result(original.config(), edited.config(), committed);
        } catch (IOException exception) {
            if (!rollbackAfterFailure(transaction, projectRoot, exception)) {
                throw recoveryFailure(transaction, exception);
            }
            throw new ZoltConfigException(
                    "Could not commit the zolt.toml and zolt.lock edit transaction. Both files were restored.");
        } catch (RuntimeException exception) {
            if (!rollbackAfterFailure(transaction, projectRoot, exception)) {
                throw recoveryFailure(transaction, exception);
            }
            throw exception;
        }
    }

    static void recover(Path transaction, Path projectRoot) {
        if (!Files.exists(transaction)) {
            return;
        }
        Path statePath = transaction.resolve(STATE);
        try {
            if (!Files.isRegularFile(statePath)) {
                deleteRecursively(transaction);
                return;
            }
            String state = Files.readString(statePath).strip();
            if (!COMMITTED.equals(state)) {
                requireRecoverableManifest(transaction, projectRoot);
                requireRecoverableLockfile(transaction, projectRoot);
                restoreBackups(transaction, projectRoot);
            }
            deleteRecursively(transaction);
        } catch (IOException exception) {
            throw new ZoltConfigException(
                    "Could not recover an interrupted manifest edit transaction at "
                            + transaction
                            + ". Preserve that directory and restore zolt.toml/zolt.lock from its backups.");
        }
    }

    private static void requireRecoverableManifest(Path transaction, Path projectRoot) throws IOException {
        Path backup = transaction.resolve("zolt.toml.backup");
        Path staged = transaction.resolve("zolt.toml.staged");
        if (!Files.isRegularFile(backup) || !Files.isRegularFile(staged)) {
            throw new IOException("manifest transaction backup or staged source is missing");
        }
        String current = Files.readString(projectRoot.resolve("zolt.toml"));
        if (current.equals(Files.readString(staged)) || current.equals(Files.readString(backup))) {
            return;
        }
        throw new IOException(
                "zolt.toml differs from both the transaction backup and staged edit; refusing to overwrite a concurrent manual change");
    }

    private static void requireRecoverableLockfile(Path transaction, Path projectRoot) throws IOException {
        Path lockfile = projectRoot.resolve("zolt.lock");
        Path backup = transaction.resolve("zolt.lock.backup");
        Path absent = transaction.resolve("zolt.lock.absent");
        boolean hasBackup = Files.isRegularFile(backup);
        boolean hasAbsentMarker = Files.isRegularFile(absent);
        if (hasBackup == hasAbsentMarker) {
            throw new IOException("lockfile transaction must contain exactly one backup or absence marker");
        }
        if (Files.exists(lockfile) && !Files.isRegularFile(lockfile)) {
            throw new IOException("zolt.lock is no longer a regular file; refusing transaction recovery");
        }
        String current = Files.isRegularFile(lockfile) ? Files.readString(lockfile) : null;
        String original = hasBackup ? Files.readString(backup) : null;
        Path staged = transaction.resolve("zolt.lock.staged");
        String requested = Files.isRegularFile(staged) ? Files.readString(staged) : null;
        if (Objects.equals(current, original)
                || (requested != null && Objects.equals(current, requested))) {
            return;
        }
        throw new IOException(
                "zolt.lock differs from both the transaction backup and staged edit; refusing to overwrite a concurrent change");
    }

    private static boolean rollbackAfterFailure(Path transaction, Path projectRoot, Exception failure) {
        try {
            recover(transaction, projectRoot);
            return true;
        } catch (RuntimeException recoveryFailure) {
            failure.addSuppressed(recoveryFailure);
            return false;
        }
    }

    private static ZoltConfigException recoveryFailure(Path transaction, Exception failure) {
        return new ZoltConfigException(ActionableError.of(
                "Manifest edit failed and automatic recovery could not safely restore zolt.toml and zolt.lock.",
                "Preserve " + transaction + " and inspect its backups before retrying.",
                failure));
    }

    private static void restoreBackups(Path transaction, Path projectRoot) throws IOException {
        Path manifestBackup = transaction.resolve("zolt.toml.backup");
        if (Files.isRegularFile(manifestBackup)) {
            AtomicLockfileWriter.writeAtomically(
                    projectRoot.resolve("zolt.toml"),
                    Files.readString(manifestBackup));
        }
        Path lockfileBackup = transaction.resolve("zolt.lock.backup");
        if (Files.isRegularFile(lockfileBackup)) {
            AtomicLockfileWriter.writeAtomically(
                    projectRoot.resolve("zolt.lock"),
                    Files.readString(lockfileBackup));
        } else if (Files.isRegularFile(transaction.resolve("zolt.lock.absent"))) {
            Files.deleteIfExists(projectRoot.resolve("zolt.lock"));
        }
    }

    private static void requireManifestUnchanged(Path manifestPath, String expected) throws IOException {
        if (!Files.readString(manifestPath).equals(expected)) {
            throw new ZoltConfigException(
                    "zolt.toml changed while dependency resolution was in progress. No changes were written; retry against the current manifest.");
        }
    }

    private static void requireLockfileUnchanged(Path lockfilePath, String expected) throws IOException {
        if (expected == null) {
            if (Files.exists(lockfilePath)) {
                throw new ZoltConfigException(
                        "zolt.lock changed while dependency resolution was in progress. No changes were written; retry the command.");
            }
            return;
        }
        if (!Files.isRegularFile(lockfilePath) || !Files.readString(lockfilePath).equals(expected)) {
            throw new ZoltConfigException(
                    "zolt.lock changed while dependency resolution was in progress. No changes were written; retry the command.");
        }
    }

    private static void writeState(Path transaction, String state) throws IOException {
        Files.writeString(transaction.resolve(STATE), state + "\n");
    }

    private static Path transactionDirectory(Path projectRoot) {
        return projectRoot.toAbsolutePath().normalize()
                .resolve(".zolt")
                .resolve(TRANSACTION_DIRECTORY);
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Path expectedName = directory.getFileName();
        if (expectedName == null || !TRANSACTION_DIRECTORY.equals(expectedName.toString())) {
            throw new IOException("Refusing to clean unexpected transaction directory " + directory);
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    record Result(ProjectConfig original, ProjectConfig updated, ResolveResult resolveResult) {
    }
}
