package sh.zolt.release.update;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.UUID;

/**
 * Cross-process installation transaction, shared with scripts/install-zolt. Both implementations
 * atomically mkdir .install-lock, write owner as PID:unique-token, and remove only their own owner
 * file and empty lock directory. Locks are never stolen: a killed owner requires explicit recovery.
 */
final class NativeInstallLease implements AutoCloseable {
    private final Path root;
    private final Path directory;
    private final String owner;

    private NativeInstallLease(Path root, Path directory, String owner) {
        this.root = root;
        this.directory = directory;
        this.owner = owner;
    }

    static NativeInstallLease acquireManaged(Path installRoot, Path executable) throws IOException, InterruptedException {
        // Caller identity is immutable across launcher switches; current state is read under the lease.
        Path root = NativeInstalledLayout.managedRoot(installRoot, executable);
        return acquire(root);
    }

    static NativeInstallLease acquire(Path installRoot) throws IOException, InterruptedException {
        return acquire(installRoot, Duration.ofMinutes(2));
    }

    static NativeInstallLease acquire(Path installRoot, Duration timeout) throws IOException, InterruptedException {
        Files.createDirectories(installRoot);
        Path root = installRoot.toRealPath();
        Path directory = root.resolve(".install-lock");
        long started = System.nanoTime();
        while (true) {
            try {
                Files.createDirectory(directory);
                break;
            } catch (FileAlreadyExistsException busy) {
                try {
                    if (!Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
                        throw new NativeUpdateException("Native installation lock is not an owned directory: " + directory + ".");
                    }
                } catch (NoSuchFileException released) {
                    continue; // The previous owner released between mkdir and inspection.
                }
                if (System.nanoTime() - started >= timeout.toNanos()) {
                    Path ownerFile = directory.resolve("owner");
                    String owner = "unknown";
                    try {
                        if (Files.isRegularFile(ownerFile, LinkOption.NOFOLLOW_LINKS) && Files.size(ownerFile) <= 1024) {
                            owner = Files.readString(ownerFile).strip();
                        }
                    } catch (NoSuchFileException released) {
                        continue;
                    }
                    throw new NativeUpdateException("Timed out waiting for native installation lock " + directory
                            + " (owner " + owner + "). Next: wait for the installer to finish. If its process has"
                            + " exited, confirm no installer is running before removing only this lock directory.");
                }
                Thread.sleep(100);
            }
        }
        String owner = ProcessHandle.current().pid() + ":" + UUID.randomUUID();
        try {
            Files.writeString(directory.resolve("owner"), owner + "\n", StandardOpenOption.CREATE_NEW);
        } catch (IOException | RuntimeException failure) {
            try { Files.delete(directory); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return new NativeInstallLease(root, directory, owner);
    }

    Path root() {
        return root;
    }

    @Override
    public void close() throws IOException {
        Path ownerFile = directory.resolve("owner");
        if (!Files.isRegularFile(ownerFile, LinkOption.NOFOLLOW_LINKS)
                || !Files.readString(ownerFile).strip().equals(owner)) {
            throw new NativeUpdateException("Native installation lock ownership changed at " + directory
                    + "; refusing to remove another owner's lock.");
        }
        Files.delete(ownerFile);
        Files.delete(directory);
    }
}
