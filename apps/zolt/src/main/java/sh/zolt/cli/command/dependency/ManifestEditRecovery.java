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

/** Recovery state machine for interrupted standalone and workspace-member manifest edits. */
final class ManifestEditRecovery {
    static final String TRANSACTION_DIRECTORY = "manifest-edit-transaction";
    static final String WORKSPACE_TRANSACTIONS_DIRECTORY = "manifest-edits";
    static final String STATE = "state";
    static final String STAGING = "STAGING";
    static final String PREPARED = "PREPARED";
    static final String MANIFEST_COMMITTED = "MANIFEST_COMMITTED";
    static final String COMMITTED = "COMMITTED";

    private ManifestEditRecovery() {
    }

    static void recover(Path transaction, Path projectRoot) {
        recover(transaction, projectRoot, projectRoot);
    }

    static void recover(Path transaction, Path manifestRoot, Path lockRoot) {
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
            if (COMMITTED.equals(state)) {
                deleteRecursively(transaction);
                return;
            }
            Path manifest = manifestRoot.resolve("zolt.toml");
            Path lockfile = lockRoot.resolve("zolt.lock");
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
                    "live zolt.toml/zolt.lock do not match a recoverable original, partial, or committed transaction state");
        } catch (IOException exception) {
            throw new ZoltConfigException(
                    "Could not recover an interrupted manifest edit transaction at "
                            + transaction
                            + ". Preserve that directory and restore zolt.toml/zolt.lock from its backups.");
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
                Path memberRoot = decodedMemberRoot(normalizedLockRoot, transaction.getFileName().toString());
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
                "Manifest edit failed and automatic recovery could not safely restore zolt.toml and zolt.lock.",
                "Preserve " + transaction + " and inspect its backups before retrying.",
                failure));
    }

    static void writeState(Path transaction, String state) throws IOException {
        Files.writeString(transaction.resolve(STATE), state + "\n");
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
