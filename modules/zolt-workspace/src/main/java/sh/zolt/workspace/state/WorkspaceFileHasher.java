package sh.zolt.workspace.state;

import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides, per file, whether the recorded content hash still holds or the bytes must be read again.
 *
 * <p>The rule is the whole point of the persisted table: a file is re-read only when its size,
 * modification time, or file key differs from what was recorded beside the hash, or when the row is
 * inside the racy-clean window (see {@link WorkspaceFileState}), or when paranoid mode is on. A warm
 * command therefore stats every input and reads none of them.
 *
 * <p>Everything observed is recorded, so the table the command persists is a full replacement for
 * the source sets it swept and a carry-forward for the ones it did not.
 */
final class WorkspaceFileHasher {
    private static final String MISSING = "missing";

    private final Path workspaceRoot;
    private final WorkspaceFileState previous;
    private final boolean paranoid;
    private final Map<Path, String> hashes = new LinkedHashMap<>();
    private final Map<String, WorkspaceFileRecord> observed = new LinkedHashMap<>();
    private final Set<String> sweptScopes = new LinkedHashSet<>();
    private long bytesHashed;
    private int filesHashed;
    private int filesStatted;
    private int filesReused;

    WorkspaceFileHasher(Path workspaceRoot, WorkspaceFileState previous, boolean paranoid) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.previous = previous;
        this.paranoid = paranoid;
    }

    /** Marks one member's source set as fully read this command, so unseen rows are deleted files. */
    void sweep(String member, WorkspaceFileKind kind) {
        if (kind.swept()) {
            sweptScopes.add(member + "|" + kind.id());
        }
    }

    /**
     * Discards what this command already learned about one member's source set.
     *
     * <p>The per-command memo assumes a file does not change while the command runs, which holds for
     * everything a build reads and not for what it writes. A member whose build just regenerated its
     * sources must be re-observed against the bytes on disk now, or the state would record the stat
     * from before the build beside a hash of content that no longer exists — costing the next command
     * a re-read and a spurious dirty reason.
     */
    void forget(String member, WorkspaceFileKind kind) {
        String scope = member + "|" + kind.id();
        observed.entrySet().removeIf(entry -> {
            if (!entry.getValue().scope().equals(scope)) {
                return false;
            }
            hashes.remove(workspaceRoot.resolve(entry.getKey()).toAbsolutePath().normalize());
            return true;
        });
        sweptScopes.remove(scope);
    }

    String hash(Path path, WorkspaceFileKind kind, String member) {
        return hash(path, null, kind, member);
    }

    /**
     * {@code attributes} is the stat the caller already paid for while walking; a null asks for one
     * here. Either way it is taken only after the per-command memo misses, so re-asking for a file
     * costs nothing.
     */
    String hash(
            Path path,
            BasicFileAttributes attributes,
            WorkspaceFileKind kind,
            String member) {
        Path normalized = path.toAbsolutePath().normalize();
        String cached = hashes.get(normalized);
        if (cached != null) {
            return cached;
        }
        BasicFileAttributes stat = attributes == null ? attributes(normalized).orElse(null) : attributes;
        filesStatted++;
        String hash = stat == null || !stat.isRegularFile()
                ? MISSING
                : resolve(normalized, stat, kind, member);
        hashes.put(normalized, hash);
        return hash;
    }

    private String resolve(
            Path path,
            BasicFileAttributes stat,
            WorkspaceFileKind kind,
            String member) {
        String key = relative(path);
        long modifiedNanos = WorkspaceFileRecord.modifiedNanos(stat);
        String fileKey = WorkspaceFileRecord.fileKey(stat);
        Optional<WorkspaceFileRecord> recorded = previous.file(key);
        String hash = reusable(recorded, stat.size(), modifiedNanos, fileKey)
                ? reuse(recorded.orElseThrow())
                : read(path);
        observed.put(
                key,
                new WorkspaceFileRecord(key, kind, member, stat.size(), modifiedNanos, fileKey, hash));
        return hash;
    }

    private boolean reusable(
            Optional<WorkspaceFileRecord> recorded,
            long size,
            long modifiedNanos,
            String fileKey) {
        return !paranoid
                && recorded.isPresent()
                && previous.settled(modifiedNanos)
                && recorded.orElseThrow().matches(size, modifiedNanos, fileKey);
    }

    private String reuse(WorkspaceFileRecord recorded) {
        filesReused++;
        return recorded.hash();
    }

    private String read(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            bytesHashed += bytes.length;
            filesHashed++;
            return WorkspaceHash.bytes(bytes);
        } catch (NoSuchFileException exception) {
            return MISSING;
        } catch (IOException exception) {
            throw new BuildException("Could not hash workspace input " + path + ".", exception);
        }
    }

    /**
     * The table to persist: every row observed this command, plus the rows of source sets this
     * command never looked at. A swept set's rows come only from the sweep, so a file that vanished
     * loses its row instead of lingering as a phantom input.
     */
    WorkspaceFileState state() {
        Map<String, WorkspaceFileRecord> merged = new LinkedHashMap<>();
        previous.files().forEach((path, record) -> {
            if (!sweptScopes.contains(record.scope())) {
                merged.put(path, record);
            }
        });
        merged.putAll(observed);
        return new WorkspaceFileState(0L, merged);
    }

    long bytesHashed() {
        return bytesHashed;
    }

    int filesHashed() {
        return filesHashed;
    }

    int filesStatted() {
        return filesStatted;
    }

    int filesReused() {
        return filesReused;
    }

    private String relative(Path path) {
        Path value = path.startsWith(workspaceRoot) ? workspaceRoot.relativize(path) : path;
        return value.toString().replace('\\', '/');
    }

    private static Optional<BasicFileAttributes> attributes(Path path) {
        try {
            return Optional.of(Files.readAttributes(
                    path.toAbsolutePath().normalize(),
                    BasicFileAttributes.class));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }
}
