package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
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
 * Serializes workspace output and state mutations across threads and processes.
 */
public final class WorkspaceMutationLock implements AutoCloseable {
    private static final String FILE_NAME = "workspace-mutation.lock";
    private static final int ROOT_CONFIRMATION_ATTEMPTS = 3;
    private static final ConcurrentHashMap<Path, ReentrantLock> PROCESS_LOCKS =
            new ConcurrentHashMap<>();
    private static final ThreadLocal<Map<Path, HeldLock>> HELD_LOCKS =
            ThreadLocal.withInitial(HashMap::new);

    private final HeldLock heldLock;
    private boolean closed;

    private WorkspaceMutationLock(HeldLock heldLock) {
        this.heldLock = heldLock;
    }

    public static WorkspaceMutationLock acquire(Path workspaceRoot) {
        Path lockPath = path(workspaceRoot);
        Map<Path, HeldLock> heldByPath = HELD_LOCKS.get();
        HeldLock existing = heldByPath.get(lockPath);
        if (existing != null) {
            existing.retain();
            return new WorkspaceMutationLock(existing);
        }
        ReentrantLock processLock =
                PROCESS_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        processLock.lock();
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
            return new WorkspaceMutationLock(heldLock);
        } catch (IOException | RuntimeException exception) {
            closeQuietly(channel);
            processLock.unlock();
            throw new BuildException(
                    "Could not acquire workspace mutation lock at "
                            + lockPath
                            + ".",
                    exception);
        }
    }

    public static <T> T withLock(Path workspaceRoot, Supplier<T> action) {
        try (WorkspaceMutationLock ignored = acquire(workspaceRoot)) {
            return action.get();
        }
    }

    /**
     * Locates the root only to choose a lock, then confirms it under the lease before running the
     * authoritative discovery and planning supplied by {@code action}.
     */
    public static <T> T withWorkspaceLock(
            Path startDirectory,
            Supplier<T> action) {
        WorkspaceDiscoveryService discovery = new WorkspaceDiscoveryService();
        for (int attempt = 0; attempt < ROOT_CONFIRMATION_ATTEMPTS; attempt++) {
            var discoveredRoot = discovery.discoverRoot(startDirectory);
            if (discoveredRoot.isEmpty()) {
                return action.get();
            }
            Path root = discoveredRoot.orElseThrow();
            try (WorkspaceMutationLock ignored = acquire(root)) {
                if (discovery.discoverRoot(startDirectory)
                        .filter(root::equals)
                        .isPresent()) {
                    return action.get();
                }
            }
        }
        throw new BuildException(
                "Workspace root changed repeatedly while acquiring its mutation lock. Retry the command.");
    }

    public static <T> T withLockIfWorkspace(
            Path startDirectory,
            Supplier<T> action) {
        if (new WorkspaceDiscoveryService().discoverRoot(startDirectory).isEmpty()) {
            return action.get();
        }
        return withWorkspaceLock(startDirectory, action);
    }

    static Path path(Path workspaceRoot) {
        return workspaceRoot.toAbsolutePath().normalize()
                .resolve(".zolt")
                .resolve(FILE_NAME);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (!heldLock.ownedByCurrentThread()) {
            throw new BuildException(
                    "Workspace mutation lock lease must be closed by its acquiring thread.");
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
                    "Could not release workspace mutation lock at "
                            + heldLock.lockPath()
                            + ".",
                    exception);
        }
        try {
            heldLock.channel().close();
        } catch (IOException exception) {
            BuildException closeFailure = new BuildException(
                    "Could not close workspace mutation lock.",
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
