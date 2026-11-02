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
 * Hashes mutable workspace inputs once per command. Persisted metadata cannot prove content is
 * unchanged: restores and editors may preserve both size and modification time. The file table
 * remains useful evidence, but only this command's own memo may avoid a second content read.
 */
final class WorkspaceFileHasher {
    private static final String MISSING = "missing";

    private final Path workspaceRoot;
    private final WorkspaceFileState previous;
    private final Map<Path, String> hashes = new LinkedHashMap<>();
    private final Map<String, WorkspaceFileRecord> observed = new LinkedHashMap<>();
    private final Set<String> sweptScopes = new LinkedHashSet<>();
    private long bytesHashed;
    private int filesHashed;
    private int filesStatted;

    WorkspaceFileHasher(Path workspaceRoot, WorkspaceFileState previous, boolean paranoid) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.previous = previous;
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
        String hash = read(path);
        observed.put(
                key,
                new WorkspaceFileRecord(key, kind, member, stat.size(), modifiedNanos, fileKey, hash));
        return hash;
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
        return 0;
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
