package sh.zolt.toolchain.install;

import sh.zolt.error.ActionableException;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.store.ToolchainStore;
import sh.zolt.toolchain.store.ToolchainStorePaths;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** A stable, kernel-released lease outside attempt-owned staging directories. */
final class JavaToolchainInstallLock implements AutoCloseable {
    private static final ConcurrentHashMap<Path, ReentrantLock> PROCESS_LOCKS = new ConcurrentHashMap<>();
    private final FileChannel channel;
    private final ReentrantLock processLock;
    private boolean closed;

    private JavaToolchainInstallLock(FileChannel channel, ReentrantLock processLock) {
        this.channel = channel;
        this.processLock = processLock;
    }

    static JavaToolchainInstallLock acquire(ToolchainStore store, LockedJavaToolchain locked) {
        Path parent = store.checked(store.installRoot(locked).getParent());
        try {
            Files.createDirectories(parent);
        } catch (IOException exception) {
            throw failure(parent);
        }
        parent = ToolchainStorePaths.canonical(store.checked(parent));
        Path lock = store.checked(parent.resolve(".jdk.install.lock"));
        ReentrantLock processLock = PROCESS_LOCKS.computeIfAbsent(lock, ignored -> new ReentrantLock());
        try {
            processLock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ActionableException("Interrupted while waiting for Java toolchain installation at " + parent + ".",
                    "Retry `zolt toolchain sync`.");
        }
        FileChannel channel = null;
        try {
            store.checked(lock);
            channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            channel.lock();
            store.checked(lock);
            return new JavaToolchainInstallLock(channel, processLock);
        } catch (IOException | RuntimeException exception) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // Preserve the acquisition failure.
                }
            }
            processLock.unlock();
            throw failure(lock);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (!processLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Java toolchain lease must be closed by its acquiring thread.");
        }
        closed = true;
        try {
            channel.close();
        } catch (IOException exception) {
            throw new ActionableException("Could not release the Java toolchain installation lease.",
                    "Retry `zolt toolchain sync` after the current installer exits.");
        } finally {
            processLock.unlock();
        }
    }

    private static ActionableException failure(Path path) {
        return new ActionableException("Could not acquire Java toolchain installation lease at " + path + ".",
                "Check the managed store permissions and symlinks, then retry `zolt toolchain sync`.");
    }
}
