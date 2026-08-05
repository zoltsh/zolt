package sh.zolt.workspace.state;

import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;

/**
 * One tracked input file: what it was, and what the filesystem said about it when it was hashed.
 *
 * <p>{@code size}, {@code modifiedNanos}, and {@code fileKey} are captured in the same breath as
 * {@code hash}, which is what makes the comparison sound — an edit after the read moves the metadata
 * away from what was recorded, so the next command re-hashes rather than trusting a stale digest.
 * The modification time is kept at the filesystem's own resolution ({@link
 * BasicFileAttributes#lastModifiedTime()} converted to nanoseconds) rather than truncated to
 * milliseconds, because the residual same-timestamp race shrinks with every digit kept.
 */
public record WorkspaceFileRecord(
        String path,
        WorkspaceFileKind kind,
        String member,
        long size,
        long modifiedNanos,
        String fileKey,
        String hash) {
    public WorkspaceFileRecord {
        path = path == null ? "" : path;
        member = member == null ? "" : member;
        fileKey = fileKey == null ? "" : fileKey;
        hash = hash == null ? "" : hash;
    }

    /** The scope a sweep replaces wholesale: one member's one source set. */
    public String scope() {
        return member + "|" + kind.id();
    }

    /**
     * Whether this record still describes the file the filesystem is reporting now. The file key is
     * compared only when both sides have one, so a filesystem that does not expose inode identity
     * degrades to size and modification time rather than forcing a permanent re-hash.
     */
    boolean matches(long currentSize, long currentModifiedNanos, String currentFileKey) {
        if (size != currentSize || modifiedNanos != currentModifiedNanos) {
            return false;
        }
        return fileKey.isEmpty() || currentFileKey.isEmpty() || fileKey.equals(currentFileKey);
    }

    static long modifiedNanos(BasicFileAttributes attributes) {
        return attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS);
    }

    static String fileKey(BasicFileAttributes attributes) {
        Object key = attributes.fileKey();
        return key == null ? "" : key.toString();
    }
}
