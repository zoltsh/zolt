package sh.zolt.build.compile;

import sh.zolt.home.UserGlobalDirectory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class JavacWorkerDaemon {
    private static final String MAIN_CLASS = "sh.zolt.javac.JavacWorkerMain";
    private static final int CONNECT_TIMEOUT_MILLIS = 500;
    private static final int REQUEST_TIMEOUT_MILLIS = (int) Duration.ofMinutes(30).toMillis();
    private static final long START_TIMEOUT_NANOS = Duration.ofSeconds(5).toNanos();
    private static final Map<Path, Object> STARTUP_LOCKS = new ConcurrentHashMap<>();

    private JavacWorkerDaemon() {
    }

    static Optional<JavacRunner.ProcessResult> compile(
            Path javac,
            Path workerJar,
            int kind,
            List<String> arguments) {
        if ("false".equalsIgnoreCase(System.getProperty("zolt.javac.worker.persistent", "true"))) {
            return Optional.empty();
        }
        Path statePath;
        try {
            statePath = statePath(javac, workerJar, runtimeDirectory());
        } catch (IOException exception) {
            return Optional.empty();
        }
        Optional<ServerMetadata> existing = readMetadata(statePath);
        if (existing.isPresent()) {
            try {
                return Optional.of(request(existing.orElseThrow(), kind, arguments));
            } catch (IOException ignored) {
                // Replace stale server state under the cross-process startup lock.
            }
        }
        try {
            ServerMetadata server = ensureServer(
                    javac,
                    workerJar,
                    statePath,
                    existing.orElse(null));
            return Optional.of(request(server, kind, arguments));
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    static Path statePath(Path javac, Path workerJar, Path runtimeDirectory) throws IOException {
        Path normalizedJavac = javac.toAbsolutePath().normalize();
        Path normalizedWorker = workerJar.toAbsolutePath().normalize();
        String identity = normalizedJavac
                + "\n"
                + normalizedWorker
                + "\n"
                + Files.size(normalizedWorker)
                + "\n"
                + Files.getLastModifiedTime(normalizedWorker).toMillis();
        return runtimeDirectory.resolve("worker-" + sha256(identity).substring(0, 24) + ".state");
    }

    private static ServerMetadata ensureServer(
            Path javac,
            Path workerJar,
            Path statePath,
            ServerMetadata failedServer) throws IOException, InterruptedException {
        Object startupLock = STARTUP_LOCKS.computeIfAbsent(statePath, ignored -> new Object());
        synchronized (startupLock) {
            return ensureServerCrossProcess(javac, workerJar, statePath, failedServer);
        }
    }

    private static ServerMetadata ensureServerCrossProcess(
            Path javac,
            Path workerJar,
            Path statePath,
            ServerMetadata failedServer) throws IOException, InterruptedException {
        createPrivateDirectory(statePath.getParent());
        Path lockPath = statePath.resolveSibling(statePath.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(
                        lockPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            Optional<ServerMetadata> current = readMetadata(statePath);
            if (current.isPresent() && !current.orElseThrow().equals(failedServer)) {
                return current.orElseThrow();
            }
            Files.deleteIfExists(statePath);
            Process process = startServer(javac, workerJar, statePath);
            long deadline = System.nanoTime() + START_TIMEOUT_NANOS;
            while (System.nanoTime() < deadline) {
                Optional<ServerMetadata> started = readMetadata(statePath);
                if (started.isPresent()) {
                    return started.orElseThrow();
                }
                if (!process.isAlive()) {
                    throw new IOException("javac worker server exited during startup");
                }
                Thread.sleep(20);
            }
            process.destroyForcibly();
            throw new IOException("timed out starting javac worker server");
        }
    }

    private static Process startServer(Path javac, Path workerJar, Path statePath) throws IOException {
        Path java = javac.toAbsolutePath().normalize().resolveSibling(javaExecutableName(javac));
        Path log = statePath.resolveSibling(statePath.getFileName() + ".log");
        return new ProcessBuilder(
                java.toString(),
                "-classpath",
                workerJar.toAbsolutePath().normalize().toString(),
                MAIN_CLASS,
                "--server",
                statePath.toString())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
    }

    private static JavacRunner.ProcessResult request(
            ServerMetadata server,
            int kind,
            List<String> arguments) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()),
                    CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(REQUEST_TIMEOUT_MILLIS);
            DataOutputStream request = new DataOutputStream(socket.getOutputStream());
            request.writeInt(JavacWorkerWire.MAGIC);
            request.writeInt(JavacWorkerWire.PROTOCOL_VERSION);
            JavacWorkerWire.writeString(request, server.token());
            JavacWorkerWire.writeRequest(request, kind, arguments);
            request.flush();
            return JavacWorkerWire.readResponse(new DataInputStream(socket.getInputStream()));
        }
    }

    private static Optional<ServerMetadata> readMetadata(Path statePath) {
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
            if (Integer.parseInt(values.getOrDefault("version", "-1")) != JavacWorkerWire.PROTOCOL_VERSION) {
                return Optional.empty();
            }
            int port = Integer.parseInt(values.getOrDefault("port", "-1"));
            String token = values.getOrDefault("token", "");
            if (port < 1 || port > 65_535 || token.length() != 64) {
                return Optional.empty();
            }
            return Optional.of(new ServerMetadata(port, token));
        } catch (IOException | NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static Path runtimeDirectory() {
        String configured = System.getProperty("zolt.javac.worker.runtimeDirectory", "");
        if (!configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return UserGlobalDirectory.runtime("javac");
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

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String javaExecutableName(Path javac) {
        String fileName = javac.getFileName().toString();
        return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".exe") ? "java.exe" : "java";
    }

    private record ServerMetadata(int port, String token) {
    }
}
