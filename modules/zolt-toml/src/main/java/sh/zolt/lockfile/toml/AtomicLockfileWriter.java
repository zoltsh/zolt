package sh.zolt.lockfile.toml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Writes lockfile text through a unique sibling and replaces the target atomically when supported. */
public final class AtomicLockfileWriter {
    private static final int MOVE_ATTEMPTS = 8;
    private static final long MOVE_RETRY_MILLIS = 10L;
    private static final ConcurrentHashMap<Path, ReentrantLock> TARGET_LOCKS =
            new ConcurrentHashMap<>();

    private AtomicLockfileWriter() {
    }

    public static void write(Path target, String content) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        ReentrantLock targetLock =
                TARGET_LOCKS.computeIfAbsent(normalized, ignored -> new ReentrantLock());
        targetLock.lock();
        try {
            writeLocked(normalized, content);
        } finally {
            targetLock.unlock();
        }
    }

    private static void writeLocked(Path normalized, String content)
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
            } catch (AtomicMoveNotSupportedException ignored) {
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
