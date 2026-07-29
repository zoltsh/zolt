package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes workspace output and state mutations across threads and processes.
 */
public final class WorkspaceMutationLock implements AutoCloseable {
    private static final String FILE_NAME = "workspace-mutation.lock";
    private static final ConcurrentHashMap<Path, ReentrantLock> PROCESS_LOCKS =
            new ConcurrentHashMap<>();

    private final Path lockPath;
    private final ReentrantLock processLock;
    private final FileChannel channel;
    private final FileLock fileLock;
    private boolean closed;

    private WorkspaceMutationLock(
            Path lockPath,
            ReentrantLock processLock,
            FileChannel channel,
            FileLock fileLock) {
        this.lockPath = lockPath;
        this.processLock = processLock;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    public static WorkspaceMutationLock acquire(Path workspaceRoot) {
        Path lockPath = path(workspaceRoot);
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
            return new WorkspaceMutationLock(
                    lockPath,
                    processLock,
                    channel,
                    channel.lock());
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
        closed = true;
        RuntimeException failure = null;
        try {
            fileLock.release();
        } catch (IOException exception) {
            failure = new BuildException(
                    "Could not release workspace mutation lock at "
                            + lockPath
                            + ".",
                    exception);
        }
        try {
            channel.close();
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
            processLock.unlock();
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
}
