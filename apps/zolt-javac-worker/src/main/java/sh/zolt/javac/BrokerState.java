package sh.zolt.javac;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;

/** The broker's rendezvous file: the port, the shared secret, and the protocol clients must speak. */
final class BrokerState {
    private BrokerState() {
    }

    static String token() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static void write(Path statePath, int port, String token) throws IOException {
        Files.createDirectories(statePath.getParent());
        Path temporary = statePath.resolveSibling(
                statePath.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        Files.writeString(temporary, """
                version=%d
                port=%d
                token=%s
                pid=%d
                """.formatted(BrokerProtocol.VERSION, port, token, ProcessHandle.current().pid()));
        restrictPermissions(temporary, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        try {
            Files.move(temporary, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Removes the rendezvous file only when this broker still owns it. */
    static void deleteOwned(Path statePath, String token) {
        try {
            if (Files.isRegularFile(statePath) && Files.readString(statePath).contains("token=" + token + "\n")) {
                Files.deleteIfExists(statePath);
            }
        } catch (IOException ignored) {
            // A later client replaces stale state.
        }
    }

    private static void restrictPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and other non-POSIX file systems use their native user permissions.
        }
    }
}
