package sh.zolt.cli.command.dependency;

import sh.zolt.error.ActionableError;
import sh.zolt.lockfile.toml.AtomicLockfileWriter;
import sh.zolt.lockfile.toml.AtomicLockfileWriter.FileSnapshot;
import sh.zolt.toml.ZoltConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Recovery state machine for interrupted standalone, member, and workspace-root manifest edits.
 *
 * <p>A journal label is only ever a claim about what was written. Once the label admits that a live
 * file may have been replaced, the decision comes from live file content and nothing else — the
 * design §19.3 matrix implemented in {@link #recoverFromLiveState}.
 */
final class ManifestEditRecovery {
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
        try {
            if (!ManifestEditJournal.hasState(transaction)) {
                ManifestEditJournal.deleteRecursively(transaction);
                return;
            }
            String state = ManifestEditJournal.state(transaction);
            if (ManifestEditJournal.COMMITTED.equals(state)) {
                ManifestEditJournal.deleteRecursively(transaction);
                return;
            }
            if (isPreCommit(state) && !liveManifestIsTheStagedCopy(transaction, normalizedManifestRoot)) {
                // Neither file was written. COMMITTING is recorded before the first live-file
                // mutation, so a journal that never reached it changed nothing — and its live
                // manifest may still differ from both recorded copies because of a benign
                // concurrent edit made while the resolve ran. Refusing on that difference would
                // leave a journal that blocks every later mutation (design §19.1).
                ManifestEditJournal.deleteRecursively(transaction);
                return;
            }
            // COMMITTING, the MANIFEST_COMMITTED label a journal written before COMMITTING existed
            // recorded after its manifest replacement, any state this release does not know, and a
            // pre-commit journal contradicted by its own live manifest all name the same window: a
            // live file may already have been replaced. Only live content decides for any of them.
            recoverFromLiveState(transaction, normalizedManifestRoot, normalizedLockRoot);
        } catch (IOException exception) {
            throw new ZoltConfigException(
                    "Could not recover an interrupted manifest edit transaction at "
                            + transaction
                            + ". Preserve that directory and restore the manifest and zolt.lock from its backups.");
        }
    }

    private static boolean isPreCommit(String state) {
        return ManifestEditJournal.STAGING.equals(state) || ManifestEditJournal.PREPARED.equals(state);
    }

    /**
     * The design §19.3 live-state matrix, the only classifier used once a live file may have been
     * replaced: original/original cleans up, staged/original restores the original manifest,
     * staged/staged completes the commit and cleans up, original/staged restores the original lock,
     * and content matching neither recorded copy is refused rather than overwritten.
     */
    private static void recoverFromLiveState(Path transaction, Path manifestRoot, Path lockRoot)
            throws IOException {
        Path manifest = ManifestEditJournal.liveManifest(transaction, manifestRoot);
        Path lockfile = lockRoot.resolve("zolt.lock");
        String originalManifest = ManifestEditJournal.originalManifest(transaction);
        String stagedManifest = ManifestEditJournal.stagedManifest(transaction);
        String currentManifest = Files.readString(manifest);
        FileSnapshot originalLock = ManifestEditJournal.originalLock(transaction);
        FileSnapshot currentLock = AtomicLockfileWriter.capture(lockfile);
        FileSnapshot stagedLock = ManifestEditJournal.stagedLock(transaction);

        boolean manifestIsOriginal = currentManifest.equals(originalManifest);
        boolean manifestIsStaged = currentManifest.equals(stagedManifest);
        boolean lockIsOriginal = currentLock.equals(originalLock);
        boolean lockIsStaged = stagedLock != null && currentLock.equals(stagedLock);
        if ((manifestIsOriginal && lockIsOriginal) || (manifestIsStaged && lockIsStaged)) {
            ManifestEditJournal.deleteRecursively(transaction);
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
            ManifestEditJournal.deleteRecursively(transaction);
            return;
        }
        if (manifestIsOriginal || manifestIsStaged) {
            throw unrecognizedLiveContent(
                    transaction,
                    lockfile,
                    ManifestEditJournal.lockBackup(transaction),
                    ManifestEditJournal.lockStaged(transaction),
                    ManifestEditJournal.digest(currentLock),
                    ManifestEditJournal.digest(originalLock),
                    ManifestEditJournal.digest(stagedLock));
        }
        throw unrecognizedLiveContent(
                transaction,
                manifest,
                ManifestEditJournal.manifestBackup(transaction),
                ManifestEditJournal.manifestStaged(transaction),
                ManifestEditJournal.digest(currentManifest),
                ManifestEditJournal.digest(originalManifest),
                ManifestEditJournal.digest(stagedManifest));
    }

    /**
     * Whether a journal that claims to have written nothing is contradicted by the live manifest.
     *
     * <p>Any read failure answers no, so an unreadable or incomplete pre-commit journal is cleaned
     * exactly as it was before rather than wedging every later mutation (design §19.1).
     */
    private static boolean liveManifestIsTheStagedCopy(Path transaction, Path manifestRoot) {
        try {
            Path staged = ManifestEditJournal.manifestStaged(transaction);
            Path manifest = ManifestEditJournal.liveManifest(transaction, manifestRoot);
            return Files.isRegularFile(staged)
                    && Files.isRegularFile(manifest)
                    && Files.readString(manifest).equals(Files.readString(staged));
        } catch (IOException exception) {
            return false;
        }
    }

    private static ZoltConfigException unrecognizedLiveContent(
            Path transaction,
            Path live,
            Path backup,
            Path staged,
            String liveDigest,
            String originalDigest,
            String stagedDigest) {
        return new ZoltConfigException(ActionableError.of(
                "Could not recover the interrupted manifest edit journal %s: %s is %s, which is neither the original %s nor the staged %s that journal recorded, so Zolt will not overwrite it."
                        .formatted(transaction, live, liveDigest, originalDigest, stagedDigest),
                "Compare %s against %s and %s, keep the content you want, then remove %s and retry."
                        .formatted(live, backup, staged, transaction)));
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
        Path journals = root.resolve(".zolt").resolve(ManifestEditJournal.JOURNALS_DIRECTORY);
        if (!Files.exists(journals, LinkOption.NOFOLLOW_LINKS)) {
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
            if (!Files.isDirectory(journal, LinkOption.NOFOLLOW_LINKS)) {
                // Only a directory can be a journal, so anything else here is not Zolt's to read.
                continue;
            }
            recoverJournal(journal, root);
        }
    }

    private static void recoverJournal(Path journal, Path root) {
        if (!ManifestEditJournal.hasState(journal)) {
            // No state record means no committed change; recover() cleans it up.
            recover(journal, root, root);
            return;
        }
        Path manifestRoot;
        try {
            manifestRoot = ManifestEditJournal.recordedManifestRoot(journal, root);
        } catch (IOException | IllegalArgumentException exception) {
            throw new ZoltConfigException(ActionableError.of(
                    "Manifest edit journal " + journal + " does not name a manifest inside " + root + ".",
                    "Remove " + journal + " after inspecting its backups, then retry.",
                    exception));
        }
        recover(journal, manifestRoot, root);
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
}
