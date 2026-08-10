package sh.zolt.lockfile.toml;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/**
 * Serializes lockfile mutations across threads and processes, then replaces through a unique
 * sibling atomically when supported.
 */
public final class AtomicLockfileWriter {
    private static final int MOVE_ATTEMPTS = 8;
    private static final long MOVE_RETRY_MILLIS = 10L;
    private static final ConcurrentHashMap<Path, ReentrantLock> TARGET_LOCKS =
            new ConcurrentHashMap<>();

    private AtomicLockfileWriter() {
    }

    public static void write(Path target, String content) throws IOException {
        mutate(target, ignored -> content, false);
    }

    /**
     * Replaces {@code target} only when the filesystem supports an atomic move. Transactions that
     * coordinate multiple files use this stricter form so an unsupported filesystem fails before
     * exposing a partially replaced file.
     */
    public static void writeAtomically(Path target, String content) throws IOException {
        mutate(target, ignored -> content, true);
    }

    /**
     * Runs one cross-process read-modify-write transaction for {@code target}.
     */
    public static void update(
            Path target,
            UnaryOperator<String> mutation) throws IOException {
        mutate(target, mutation, false);
    }

    /**
     * Runs one cross-process read-modify-write transaction and returns the exact committed content.
     */
    public static String updateAndReturn(
            Path target,
            UnaryOperator<String> mutation) throws IOException {
        return mutate(target, mutation, false);
    }

    private static String mutate(
            Path target,
            UnaryOperator<String> mutation,
            boolean requireAtomicMove) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        ReentrantLock targetLock =
                TARGET_LOCKS.computeIfAbsent(normalized, ignored -> new ReentrantLock());
        targetLock.lock();
        try {
            Path parent = normalized.getParent();
            if (parent == null) {
                throw new IOException("Lockfile path has no parent: " + normalized);
            }
            Files.createDirectories(parent);
            Path mutationLock = parent.resolve(".zolt")
                    .resolve("lockfile-mutations")
                    .resolve(normalized.getFileName() + ".lock");
            Files.createDirectories(mutationLock.getParent());
            try (FileChannel channel = FileChannel.open(
                    mutationLock,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                String current = Files.isRegularFile(normalized)
                        ? Files.readString(normalized, StandardCharsets.UTF_8)
                        : "";
                String committed = mutation.apply(current);
                writeLocked(normalized, committed, requireAtomicMove);
                return committed;
            }
        } finally {
            targetLock.unlock();
        }
    }

    private static void writeLocked(Path normalized, String content, boolean requireAtomicMove)
            throws IOException {
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("Lockfile path has no parent: " + normalized);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(
                parent,
                "." + normalized.getFileName() + ".",
                ".tmp");
        boolean committed = false;
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                moveWithRetry(
                        temporary, normalized,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                if (requireAtomicMove) {
                    throw exception;
                }
                moveWithRetry(
                        temporary, normalized,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            committed = true;
        } finally {
            if (!committed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void moveWithRetry(
            Path source,
            Path target,
            StandardCopyOption... options) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(source, target, options);
                return;
            } catch (AccessDeniedException exception) {
                if (attempt >= MOVE_ATTEMPTS) {
                    throw exception;
                }
                pauseBeforeRetry(attempt, target);
            }
        }
    }

    private static void pauseBeforeRetry(int attempt, Path target)
            throws IOException {
        try {
            Thread.sleep(MOVE_RETRY_MILLIS * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "Interrupted while replacing lockfile " + target + ".",
                    exception);
        }
    }
}
