package sh.zolt.build;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serializes project output and state mutations across threads and processes.
 */
public final class ProjectMutationLock implements AutoCloseable {
    private static final String FILE_NAME = "project-mutation.lock";
    private static final ConcurrentHashMap<Path, ReentrantLock> PROCESS_LOCKS =
            new ConcurrentHashMap<>();
    private static final ThreadLocal<Map<Path, HeldLock>> HELD_LOCKS =
            ThreadLocal.withInitial(HashMap::new);

    private final HeldLock heldLock;
    private boolean closed;

    private ProjectMutationLock(HeldLock heldLock) {
        this.heldLock = heldLock;
    }

    public static ProjectMutationLock acquire(Path projectRoot) {
        return acquire(projectRoot, FILE_NAME);
    }

    /** Preserves the workspace lease separately from member leases, including a `.` root member. */
    public static ProjectMutationLock acquireWorkspace(Path workspaceRoot) {
        return acquire(workspaceRoot, "workspace-mutation.lock");
    }

    private static ProjectMutationLock acquire(Path projectRoot, String fileName) {
        Path lockPath = path(projectRoot, fileName);
        Map<Path, HeldLock> heldByPath = HELD_LOCKS.get();
        HeldLock existing = heldByPath.get(lockPath);
        if (existing != null) {
            existing.retain();
            return new ProjectMutationLock(existing);
        }
        ReentrantLock processLock =
                PROCESS_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        try {
            processLock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BuildException("Interrupted while waiting for project mutation lock at " + lockPath + ".", exception);
        }
        FileChannel channel = null;
        try {
            Files.createDirectories(lockPath.getParent());
            channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            HeldLock heldLock = new HeldLock(
                    lockPath,
                    processLock,
                    channel,
                    channel.lock());
            heldByPath.put(lockPath, heldLock);
            return new ProjectMutationLock(heldLock);
        } catch (IOException | RuntimeException exception) {
            closeQuietly(channel);
            processLock.unlock();
            throw new BuildException(
                    "Could not acquire project mutation lock at "
                            + lockPath
                            + ".",
                    exception);
        }
    }

    public static <T> T withLock(Path workspaceRoot, Supplier<T> action) {
        try (ProjectMutationLock ignored = acquire(workspaceRoot)) {
            return action.get();
        }
    }

    private static Path path(Path workspaceRoot, String fileName) {
        try {
            return workspaceRoot.toRealPath().resolve(".zolt").resolve(fileName);
        } catch (IOException exception) {
            throw new BuildException("Could not locate project mutation lock root at " + workspaceRoot + ".", exception);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (!heldLock.ownedByCurrentThread()) {
            throw new BuildException(
                    "Project mutation lock lease must be closed by its acquiring thread.");
        }
        closed = true;
        if (!heldLock.releaseLease()) {
            return;
        }
        Map<Path, HeldLock> heldByPath = HELD_LOCKS.get();
        heldByPath.remove(heldLock.lockPath());
        if (heldByPath.isEmpty()) {
            HELD_LOCKS.remove();
        }
        RuntimeException failure = null;
        try {
            heldLock.fileLock().release();
        } catch (IOException exception) {
            failure = new BuildException(
                    "Could not release project mutation lock at "
                            + heldLock.lockPath()
                            + ".",
                    exception);
        }
        try {
            heldLock.channel().close();
        } catch (IOException exception) {
            BuildException closeFailure = new BuildException(
                    "Could not close project mutation lock.",
                    exception);
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        } finally {
            heldLock.processLock().unlock();
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Preserve the acquisition failure.
        }
    }

    private static final class HeldLock {
        private final Path lockPath;
        private final ReentrantLock processLock;
        private final FileChannel channel;
        private final FileLock fileLock;
        private final Thread owner;
        private int leases = 1;

        private HeldLock(
                Path lockPath,
                ReentrantLock processLock,
                FileChannel channel,
                FileLock fileLock) {
            this.lockPath = lockPath;
            this.processLock = processLock;
            this.channel = channel;
            this.fileLock = fileLock;
            this.owner = Thread.currentThread();
        }

        private void retain() {
            leases++;
        }

        private boolean releaseLease() {
            leases--;
            return leases == 0;
        }

        private boolean ownedByCurrentThread() {
            return owner == Thread.currentThread();
        }

        private Path lockPath() {
            return lockPath;
        }

        private ReentrantLock processLock() {
            return processLock;
        }

        private FileChannel channel() {
            return channel;
        }

        private FileLock fileLock() {
            return fileLock;
        }
    }
}
