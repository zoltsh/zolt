package sh.zolt.workspace.state;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The persisted per-file table, plus the fence that decides which of its rows may be trusted.
 *
 * <p>{@code fenceNanos} is the modification time of the state file the table was read from. A file
 * whose own modification time is not strictly older than that is <em>racily clean</em>: the last
 * command could have read its bytes and the user could have edited it afterwards, both inside one
 * tick of the filesystem clock, leaving size and timestamp unchanged. Such a row proves nothing and
 * the file is re-hashed. This is exactly git's index rule, and with nanosecond timestamps the racy
 * set on a warm command is empty.
 *
 * <p>A fence of zero means "no fence known" — an unread or freshly parsed table — and every row is
 * treated as racily clean, so the table can never be trusted more than the store that loaded it.
 */
public record WorkspaceFileState(long fenceNanos, Map<String, WorkspaceFileRecord> files) {
    public WorkspaceFileState {
        files = Map.copyOf(new LinkedHashMap<>(files));
    }

    public static WorkspaceFileState empty() {
        return new WorkspaceFileState(0L, Map.of());
    }

    public Optional<WorkspaceFileRecord> file(String path) {
        return Optional.ofNullable(files.get(path));
    }

    /** The same table read behind a known fence. */
    public WorkspaceFileState withFence(long fence) {
        return fence == fenceNanos ? this : new WorkspaceFileState(fence, files);
    }

    /** Whether a file last modified at {@code modifiedNanos} is old enough for its row to be trusted. */
    boolean settled(long modifiedNanos) {
        return fenceNanos > 0L && modifiedNanos < fenceNanos;
    }
}
