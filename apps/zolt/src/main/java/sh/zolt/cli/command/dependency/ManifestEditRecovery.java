package sh.zolt.cli.command.dependency;

import sh.zolt.error.ActionableError;
import sh.zolt.lockfile.toml.AtomicLockfileWriter;
import sh.zolt.lockfile.toml.AtomicLockfileWriter.FileSnapshot;
import sh.zolt.toml.ZoltConfigException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

/** Recovery state machine for interrupted standalone, member, and workspace-root manifest edits. */
final class ManifestEditRecovery {
    static final String TRANSACTION_DIRECTORY = "manifest-edit-transaction";
    static final String WORKSPACE_TRANSACTIONS_DIRECTORY = "manifest-edits";
    static final String STATE = "state";
    static final String STAGING = "STAGING";
    static final String PREPARED = "PREPARED";
    static final String MANIFEST_COMMITTED = "MANIFEST_COMMITTED";
    static final String COMMITTED = "COMMITTED";
    private static final String MANIFEST_ROOT = "manifest-root";
    private static final String MANIFEST_PATH = "manifest-path";

    private ManifestEditRecovery() {
    }

    static void recover(Path transaction, Path projectRoot) {
        recover(transaction, projectRoot, projectRoot);
    }

    static void recover(Path transaction, Path manifestRoot, Path lockRoot) {
        if (!Files.exists(transaction)) {
            return;
        }
        Path normalizedManifestRoot = manifestRoot.toAbsolutePath().normalize();
        Path normalizedLockRoot = lockRoot.toAbsolutePath().normalize();
        Path statePath = transaction.resolve(STATE);
        try {
            if (!Files.isRegularFile(statePath)) {
                deleteRecursively(transaction);
                return;
            }
            String state = Files.readString(statePath).strip();
            if (COMMITTED.equals(state)) {
                deleteRecursively(transaction);
                return;
            }
            Path manifest = normalizedManifestRoot.resolve(recordedManifestPath(transaction)).normalize();
            if (!manifest.startsWith(normalizedManifestRoot)) {
                throw new IOException("manifest path escapes its recorded root");
            }
            Path lockfile = normalizedLockRoot.resolve("zolt.lock");
            String originalManifest = requiredContent(transaction.resolve("zolt.toml.backup"));
            String stagedManifest = requiredContent(transaction.resolve("zolt.toml.staged"));
            String currentManifest = Files.readString(manifest);
            FileSnapshot originalLock = originalLockSnapshot(transaction);
            FileSnapshot currentLock = AtomicLockfileWriter.capture(lockfile);
            FileSnapshot stagedLock = stagedLockSnapshot(transaction);

            boolean manifestIsOriginal = currentManifest.equals(originalManifest);
            boolean manifestIsStaged = currentManifest.equals(stagedManifest);
            boolean lockIsOriginal = currentLock.equals(originalLock);
            boolean lockIsStaged = stagedLock != null && currentLock.equals(stagedLock);
            if ((manifestIsOriginal && lockIsOriginal) || (manifestIsStaged && lockIsStaged)) {
                deleteRecursively(transaction);
                return;
            }
            if ((manifestIsStaged && lockIsOriginal) || (manifestIsOriginal && lockIsStaged)) {
                rollbackKnownPartialCommit(
                        manifest,
                        lockfile,
                        currentManifest,
                        currentLock,
                        originalManifest,
                        originalLock,
                        manifestIsStaged);
                deleteRecursively(transaction);
                return;
            }
            throw new IOException(
                    "live manifest/zolt.lock do not match a recoverable original, partial, or committed transaction state");
        } catch (IOException exception) {
            throw new ZoltConfigException(
                    "Could not recover an interrupted manifest edit transaction at "
                            + transaction
                            + ". Preserve that directory and restore the manifest and zolt.lock from its backups.");
        }
    }

    private static void rollbackKnownPartialCommit(
            Path manifest,
            Path lockfile,
            String currentManifest,
            FileSnapshot currentLock,
            String originalManifest,
            FileSnapshot originalLock,
            boolean restoreManifest) throws IOException {
        AtomicLockfileWriter.compareAndSetAtomically(
                lockfile,
                currentLock,
                originalLock,
                () -> {
                    if (restoreManifest) {
                        AtomicLockfileWriter.compareAndSetAtomically(
                                manifest,
                                FileSnapshot.present(currentManifest),
                                FileSnapshot.present(originalManifest),
                                () -> {});
                    }
                });
    }

    static void recoverAll(Path projectRoot, Path lockRoot) {
        Path normalizedProject = projectRoot.toAbsolutePath().normalize();
        Path normalizedLockRoot = lockRoot.toAbsolutePath().normalize();
        Set<Path> legacyRoots = new LinkedHashSet<>();
        legacyRoots.add(normalizedProject);
        legacyRoots.add(normalizedLockRoot);
        for (Path legacyRoot : legacyRoots) {
            recover(legacyRoot.resolve(".zolt").resolve(TRANSACTION_DIRECTORY), legacyRoot, legacyRoot);
        }

        Path workspaceTransactions = normalizedLockRoot.resolve(".zolt").resolve(WORKSPACE_TRANSACTIONS_DIRECTORY);
        if (!Files.exists(workspaceTransactions)) {
            return;
        }
        if (!Files.isDirectory(workspaceTransactions)) {
            throw new ZoltConfigException(
                    "Workspace manifest transaction journal is not a directory: " + workspaceTransactions);
        }
        try (var entries = Files.list(workspaceTransactions)) {
            for (Path transaction : entries.sorted().toList()) {
                if (!Files.isDirectory(transaction)) {
                    throw new IOException("Unexpected workspace transaction entry " + transaction);
                }
                Path memberRoot = recordedManifestRoot(transaction, normalizedLockRoot);
                recover(transaction, memberRoot, normalizedLockRoot);
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new ZoltConfigException(
                    "Could not inspect workspace manifest transaction journals at " + workspaceTransactions + ".");
        }
    }

    private static Path decodedMemberRoot(Path workspaceRoot, String encodedMember) throws IOException {
        String member = new String(Base64.getUrlDecoder().decode(encodedMember), StandardCharsets.UTF_8);
        Path resolved = ".".equals(member) ? workspaceRoot : workspaceRoot.resolve(member).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IOException("Workspace transaction member escapes the workspace root");
        }
        return resolved;
    }

    private static Path recordedManifestRoot(Path transaction, Path workspaceRoot) throws IOException {
        Path recorded = transaction.resolve(MANIFEST_ROOT);
        if (!Files.isRegularFile(recorded)) {
            return decodedMemberRoot(workspaceRoot, transaction.getFileName().toString());
        }
        String relative = Files.readString(recorded).strip();
        if (relative.isBlank()) {
            throw new IOException("Workspace transaction manifest root is blank");
        }
        Path resolved = ".".equals(relative)
                ? workspaceRoot
                : workspaceRoot.resolve(relative).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IOException("Workspace transaction manifest root escapes the workspace root");
        }
        return resolved;
    }

    private static Path recordedManifestPath(Path transaction) throws IOException {
        Path recorded = transaction.resolve(MANIFEST_PATH);
        if (!Files.isRegularFile(recorded)) {
            return Path.of("zolt.toml");
        }
        String relative = Files.readString(recorded).strip();
        if (relative.isBlank()) {
            throw new IOException("Workspace transaction manifest path is blank");
        }
        Path path = Path.of(relative).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IOException("Workspace transaction manifest path escapes its root");
        }
        return path;
    }

    private static FileSnapshot originalLockSnapshot(Path transaction) throws IOException {
        Path backup = transaction.resolve("zolt.lock.backup");
        Path absent = transaction.resolve("zolt.lock.absent");
        boolean hasBackup = Files.isRegularFile(backup);
        boolean hasAbsent = Files.isRegularFile(absent);
        if (hasBackup == hasAbsent) {
            throw new IOException("lockfile transaction must contain exactly one backup or absence marker");
        }
        return hasBackup ? FileSnapshot.present(Files.readString(backup)) : FileSnapshot.absent();
    }

    private static FileSnapshot stagedLockSnapshot(Path transaction) throws IOException {
        Path staged = transaction.resolve("zolt.lock.staged");
        return Files.isRegularFile(staged) ? FileSnapshot.present(Files.readString(staged)) : null;
    }

    private static String requiredContent(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Required transaction file is missing: " + path);
        }
        return Files.readString(path);
    }

    static boolean rollbackAfterFailure(
            Path transaction,
            Path manifestRoot,
            Path lockRoot,
            Exception failure) {
        try {
            recover(transaction, manifestRoot, lockRoot);
            return true;
        } catch (RuntimeException recoveryFailure) {
            failure.addSuppressed(recoveryFailure);
            return false;
        }
    }

    static ZoltConfigException recoveryFailure(Path transaction, Exception failure) {
        return new ZoltConfigException(ActionableError.of(
                "Manifest edit failed and automatic recovery could not safely restore the manifest and zolt.lock.",
                "Preserve " + transaction + " and inspect its backups before retrying.",
                failure));
    }

    static void writeState(Path transaction, String state) throws IOException {
        Files.writeString(transaction.resolve(STATE), state + "\n");
    }

    static void writeScope(Path transaction, ManifestMutationScope scope) throws IOException {
        Path lockRoot = scope.lockRoot().toAbsolutePath().normalize();
        Path manifestRoot = scope.manifestRoot().toAbsolutePath().normalize();
        Path manifestPath = scope.manifestPath().toAbsolutePath().normalize();
        if (!manifestRoot.startsWith(lockRoot) || !manifestPath.startsWith(manifestRoot)) {
            throw new IOException("Manifest edit scope escapes its lock or manifest root");
        }
        String relativeRoot = lockRoot.equals(manifestRoot)
                ? "."
                : lockRoot.relativize(manifestRoot).toString().replace('\\', '/');
        String relativeManifest = manifestRoot.relativize(manifestPath).toString().replace('\\', '/');
        Files.writeString(transaction.resolve(MANIFEST_ROOT), relativeRoot + "\n");
        Files.writeString(transaction.resolve(MANIFEST_PATH), relativeManifest + "\n");
    }

    static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Path name = directory.getFileName();
        Path parentName = directory.getParent() == null ? null : directory.getParent().getFileName();
        boolean expected = name != null && TRANSACTION_DIRECTORY.equals(name.toString());
        expected |= parentName != null && WORKSPACE_TRANSACTIONS_DIRECTORY.equals(parentName.toString());
        if (!expected) {
            throw new IOException("Refusing to clean unexpected transaction directory " + directory);
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
