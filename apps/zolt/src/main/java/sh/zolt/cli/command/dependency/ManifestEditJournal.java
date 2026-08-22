package sh.zolt.cli.command.dependency;

import sh.zolt.lockfile.toml.AtomicLockfileWriter.FileSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;

/**
 * The on-disk format of one manifest edit journal: where it lives, what it records, and how each
 * record is written and read back (design §19.1, §19.3).
 *
 * <p>This is the single definition of that format. {@link ManifestEditCommitter} writes journals
 * through it and {@link ManifestEditRecovery} interprets them through it, so neither side spells an
 * entry name itself.
 */
final class ManifestEditJournal {
    /** Design §19.1: every journal lives under {@code .zolt/manifest-edits}. */
    static final String JOURNALS_DIRECTORY = "manifest-edits";
    /** The reserved journal name for a standalone project's own manifest. */
    static final String STANDALONE_JOURNAL = "project";
    static final String STATE = "state";
    /** Journal created; backups and the staged manifest written. No live file is touched yet. */
    static final String STAGING = "STAGING";
    /** Resolution finished and the staged lock is recorded. No live file is touched yet. */
    static final String PREPARED = "PREPARED";
    /**
     * Written inside the checked lockfile compare-and-set, before the first live-file mutation.
     * From here on the live manifest and zolt.lock may each be the original or the staged copy, so
     * recovery decides from live content alone (design §19.3).
     */
    static final String COMMITTING = "COMMITTING";
    /** Both files are committed; only the journal is left to remove. */
    static final String COMMITTED = "COMMITTED";
    private static final String MANIFEST_ROOT = "manifest-root";
    private static final String MANIFEST_PATH = "manifest-path";
    private static final String MANIFEST_BACKUP = "zolt.toml.backup";
    private static final String MANIFEST_STAGED = "zolt.toml.staged";
    private static final String LOCK_BACKUP = "zolt.lock.backup";
    private static final String LOCK_STAGED = "zolt.lock.staged";
    private static final String LOCK_ABSENT = "zolt.lock.absent";

    private ManifestEditJournal() {
    }

    static boolean hasState(Path journal) {
        return Files.isRegularFile(journal.resolve(STATE));
    }

    static String state(Path journal) throws IOException {
        return Files.readString(journal.resolve(STATE)).strip();
    }

    /**
     * Replaces the journal state through a sibling temporary and an atomic move, so a state record
     * is never observed half-written and a reader sees either the previous state or the next one.
     * The stricter atomic move is deliberate: a filesystem that cannot replace atomically must fail
     * the transition rather than expose a truncated state (design §19.4, §19.5).
     */
    static void writeState(Path journal, String state) throws IOException {
        Path target = journal.resolve(STATE);
        Path temporary = Files.createTempFile(journal, "." + STATE + ".", ".tmp");
        boolean replaced = false;
        try {
            Files.writeString(temporary, state + "\n", StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            replaced = true;
        } finally {
            if (!replaced) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    static void writeScope(Path journal, ManifestMutationScope scope) throws IOException {
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
        Files.writeString(journal.resolve(MANIFEST_ROOT), relativeRoot + "\n");
        Files.writeString(journal.resolve(MANIFEST_PATH), relativeManifest + "\n");
    }

    static void writeManifestCopies(Path journal, String original, String staged) throws IOException {
        Files.writeString(journal.resolve(MANIFEST_BACKUP), original);
        Files.writeString(journal.resolve(MANIFEST_STAGED), staged);
    }

    /** Records the captured lock as either a backup copy or an explicit absence marker. */
    static void writeOriginalLock(Path journal, FileSnapshot original) throws IOException {
        if (original.exists()) {
            Files.writeString(journal.resolve(LOCK_BACKUP), original.content());
        } else {
            Files.writeString(journal.resolve(LOCK_ABSENT), "absent\n");
        }
    }

    static void writeStagedLock(Path journal, String staged) throws IOException {
        Files.writeString(journal.resolve(LOCK_STAGED), staged);
    }

    static Path manifestBackup(Path journal) {
        return journal.resolve(MANIFEST_BACKUP);
    }

    static Path manifestStaged(Path journal) {
        return journal.resolve(MANIFEST_STAGED);
    }

    static Path lockBackup(Path journal) {
        return journal.resolve(LOCK_BACKUP);
    }

    static Path lockStaged(Path journal) {
        return journal.resolve(LOCK_STAGED);
    }

    static String originalManifest(Path journal) throws IOException {
        return requiredContent(manifestBackup(journal));
    }

    static String stagedManifest(Path journal) throws IOException {
        return requiredContent(manifestStaged(journal));
    }

    static FileSnapshot originalLock(Path journal) throws IOException {
        boolean hasBackup = Files.isRegularFile(lockBackup(journal));
        boolean hasAbsent = Files.isRegularFile(journal.resolve(LOCK_ABSENT));
        if (hasBackup == hasAbsent) {
            throw new IOException("lockfile transaction must contain exactly one backup or absence marker");
        }
        return hasBackup
                ? FileSnapshot.present(Files.readString(lockBackup(journal)))
                : FileSnapshot.absent();
    }

    /** The staged lock, or {@code null} for a journal that stopped before resolution finished. */
    static FileSnapshot stagedLock(Path journal) throws IOException {
        Path staged = lockStaged(journal);
        return Files.isRegularFile(staged) ? FileSnapshot.present(Files.readString(staged)) : null;
    }

    /** The live manifest this journal edits, verified to stay inside its recorded root. */
    static Path liveManifest(Path journal, Path manifestRoot) throws IOException {
        Path manifest = manifestRoot.resolve(recordedManifestPath(journal)).normalize();
        if (!manifest.startsWith(manifestRoot)) {
            throw new IOException("manifest path escapes its recorded root");
        }
        return manifest;
    }

    static Path recordedManifestRoot(Path journal, Path workspaceRoot) throws IOException {
        Path recorded = journal.resolve(MANIFEST_ROOT);
        if (!Files.isRegularFile(recorded)) {
            String name = journal.getFileName().toString();
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

    private static Path decodedMemberRoot(Path workspaceRoot, String encodedMember) throws IOException {
        String member = new String(Base64.getUrlDecoder().decode(encodedMember), StandardCharsets.UTF_8);
        Path resolved = ".".equals(member) ? workspaceRoot : workspaceRoot.resolve(member).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IOException("Workspace transaction member escapes the workspace root");
        }
        return resolved;
    }

    private static Path recordedManifestPath(Path journal) throws IOException {
        Path recorded = journal.resolve(MANIFEST_PATH);
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

    private static String requiredContent(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Required transaction file is missing: " + path);
        }
        return Files.readString(path);
    }

    /** Names one recorded or live content for a diagnostic without quoting the content itself. */
    static String digest(FileSnapshot snapshot) {
        if (snapshot == null) {
            return "not recorded";
        }
        return snapshot.exists() ? digest(snapshot.content()) : "absent";
    }

    static String digest(String content) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required to describe a journal.", exception);
        }
        return "sha256:"
                + HexFormat.of().formatHex(sha256.digest(content.getBytes(StandardCharsets.UTF_8)));
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
