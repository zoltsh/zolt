package sh.zolt.toml;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Atomically replaces one parsed manifest only while its captured source is unchanged. */
public final class AtomicManifestWriter {
    private AtomicManifestWriter() {
    }

    public static void writePrepared(Path path, String original, String edited) {
        if (edited.equals(original)) {
            return;
        }
        Path absolute = path.toAbsolutePath().normalize();
        Path staged = null;
        try {
            requireUnchanged(absolute, original);
            staged = Files.createTempFile(absolute.getParent(), ".zolt-manifest-", ".tmp");
            Files.writeString(staged, edited);
            copyPermissions(absolute, staged);
            requireUnchanged(absolute, original);
            moveAtomically(staged, absolute);
            staged = null;
        } catch (IOException exception) {
            throw new ZoltConfigException(
                    "Could not write dependency manifest at " + path + ". Check that it exists and is writable.");
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // Preserve the edit failure; the uniquely named sibling is safe to clean later.
                }
            }
        }
    }

    private static void requireUnchanged(Path path, String expected) throws IOException {
        if (!Files.readString(path).equals(expected)) {
            throw new ZoltConfigException(
                    "The dependency manifest changed while the edit was in progress. No changes were written; retry against the current manifest.");
        }
    }

    private static void copyPermissions(Path source, Path target) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source);
            Files.setPosixFilePermissions(target, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems retain their platform defaults.
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic manifest replacement is not supported at " + target, exception);
        }
    }
}
