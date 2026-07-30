package sh.zolt.lockfile.toml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writes lockfile text through a unique sibling and replaces the target atomically when supported. */
public final class AtomicLockfileWriter {
    private AtomicLockfileWriter() {
    }

    public static void write(Path target, String content) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
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
                Files.move(
                        temporary,
                        normalized,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary,
                        normalized,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            committed = true;
        } finally {
            if (!committed) {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
