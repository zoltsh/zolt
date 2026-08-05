package sh.zolt.build.compile;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the broker for an identity, starting one if nobody has yet.
 *
 * <p>Two commands can race for the same broker, so startup is serialized by a lock file: the loser
 * finds the winner's rendezvous file and connects to it. Anything that goes wrong here — no lock, no
 * broker, a stale port — is answered with an empty result, never an error, because a build must not
 * fail over an optimization.
 */
final class JavacBrokerLauncher {
    private static final long START_TIMEOUT_NANOS = Duration.ofSeconds(10).toNanos();
    private static final Map<Path, Object> STARTUP_LOCKS = new ConcurrentHashMap<>();

    private JavacBrokerLauncher() {
    }

    static Optional<Metadata> read(Path statePath) {
        if (!Files.isRegularFile(statePath)) {
            return Optional.empty();
        }
        try {
            Map<String, String> values = new HashMap<>();
            for (String line : Files.readAllLines(statePath)) {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    values.put(line.substring(0, separator), line.substring(separator + 1));
                }
            }
            if (Integer.parseInt(values.getOrDefault("version", "-1")) != JavacBrokerWire.VERSION) {
                return Optional.empty();
            }
            int port = Integer.parseInt(values.getOrDefault("port", "-1"));
            String token = values.getOrDefault("token", "");
            if (port < 1 || port > 65_535 || token.length() != 64) {
                return Optional.empty();
            }
            return Optional.of(new Metadata(port, token));
        } catch (IOException | NumberFormatException exception) {
            return Optional.empty();
        }
    }

    /** Starts a broker unless another command already published one for this identity. */
    static Optional<Metadata> start(JavacBrokerIdentity identity, Path statePath, Metadata failed) {
        Object startupLock = STARTUP_LOCKS.computeIfAbsent(statePath, ignored -> new Object());
        synchronized (startupLock) {
            try {
                return startCrossProcess(identity, statePath, failed);
            } catch (IOException exception) {
                return Optional.empty();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    private static Optional<Metadata> startCrossProcess(
            JavacBrokerIdentity identity,
            Path statePath,
            Metadata failed) throws IOException, InterruptedException {
        createPrivateDirectory(statePath.getParent());
        Path lockPath = statePath.resolveSibling(statePath.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            Optional<Metadata> current = read(statePath);
            if (current.isPresent() && !current.orElseThrow().equals(failed)) {
                return current;
            }
            Files.deleteIfExists(statePath);
            Process process = spawn(identity, statePath);
            long deadline = System.nanoTime() + START_TIMEOUT_NANOS;
            while (System.nanoTime() < deadline) {
                Optional<Metadata> started = read(statePath);
                if (started.isPresent()) {
                    return started;
                }
                if (!process.isAlive()) {
                    return Optional.empty();
                }
                Thread.sleep(10);
            }
            process.destroyForcibly();
            return Optional.empty();
        }
    }

    private static Process spawn(JavacBrokerIdentity identity, Path statePath) throws IOException {
        Path log = statePath.resolveSibling(statePath.getFileName() + ".log");
        return new ProcessBuilder(identity.startCommand(statePath))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        restrictPermissions(directory, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
    }

    private static void restrictPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and other non-POSIX file systems use their native user permissions.
        }
    }

    record Metadata(int port, String token) {
    }
}
