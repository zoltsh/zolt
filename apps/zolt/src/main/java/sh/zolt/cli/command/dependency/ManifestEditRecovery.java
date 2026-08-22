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
import java.util.List;
import java.util.Set;

/** Recovery state machine for interrupted standalone, member, and workspace-root manifest edits. */
final class ManifestEditRecovery {
    /** Design §19.1: every journal lives under {@code .zolt/manifest-edits}. */
    static final String JOURNALS_DIRECTORY = "manifest-edits";
    /** The reserved journal name for a standalone project's own manifest. */
    static final String STANDALONE_JOURNAL = "project";
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
            if (STAGING.equals(state) || PREPARED.equals(state)) {
                // Nothing was written. The manifest and zolt.lock are only touched inside the
                // compare-and-set callback, which flips the journal to MANIFEST_COMMITTED before it
                // returns, so a journal that never reached that state records no on-disk change.
                // Comparing content here would instead reject any benign concurrent edit made while
                // the resolve ran, and the surviving journal would then block every later mutation.
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
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(projectRoot.toAbsolutePath().normalize());
        roots.add(lockRoot.toAbsolutePath().normalize());
        for (Path root : roots) {
            recoverJournals(root);
        }
    }

    /**
     * Recovers every journal under {@code root/.zolt/manifest-edits}.
     *
     * <p>The journal area is Zolt's, but it lives in a directory a user, an editor, or a backup tool
     * can also write to. Nothing found there may permanently block every mutation: an entry that
     * cannot be a journal is ignored, a journal that recorded no on-disk change is cleaned, and the
     * one case Zolt genuinely cannot interpret fails naming exactly the one path to remove.
     */
    private static void recoverJournals(Path root) {
        Path journals = root.resolve(".zolt").resolve(JOURNALS_DIRECTORY);
        if (!Files.exists(journals, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(journals)) {
            throw new ZoltConfigException(ActionableError.of(
                    "The manifest edit journal area " + journals + " is not a directory.",
                    "Delete " + journals + ", then retry."));
        }
        List<Path> entries;
        try (var stream = Files.list(journals)) {
            entries = stream.sorted().toList();
        } catch (IOException exception) {
            throw new ZoltConfigException(ActionableError.of(
                    "Could not list the manifest edit journal area " + journals + ".",
                    "Check that " + journals + " is readable, then retry.",
                    exception));
        }
        for (Path journal : entries) {
            if (!Files.isDirectory(journal, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                // Only a directory can be a journal, so anything else here is not Zolt's to read.
                continue;
            }
            recoverJournal(journal, root);
        }
    }

    private static void recoverJournal(Path journal, Path root) {
        if (!Files.isRegularFile(journal.resolve(STATE))) {
            // No state record means no committed change; recover() cleans it up.
            recover(journal, root, root);
            return;
        }
        Path manifestRoot;
        try {
            manifestRoot = recordedManifestRoot(journal, root);
        } catch (IOException | IllegalArgumentException exception) {
            throw new ZoltConfigException(ActionableError.of(
                    "Manifest edit journal " + journal + " does not name a manifest inside " + root + ".",
                    "Remove " + journal + " after inspecting its backups, then retry.",
                    exception));
        }
        recover(journal, manifestRoot, root);
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
            String name = transaction.getFileName().toString();
            return STANDALONE_JOURNAL.equals(name)
                    ? workspaceRoot
                    : decodedMemberRoot(workspaceRoot, name);
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
        Path parentName = directory.getParent() == null ? null : directory.getParent().getFileName();
        boolean expected = parentName != null && JOURNALS_DIRECTORY.equals(parentName.toString());
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
