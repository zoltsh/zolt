package sh.zolt.build.compile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The command's side of the persistent javac broker: one session per worker identity, opened on
 * first use and kept for the life of the command.
 *
 * <p>Everything here degrades to {@link Optional#empty()}, which the caller answers with a
 * command-local worker. A missing broker, a refused handshake, a protocol the broker does not speak,
 * or a crash mid-build all take that path, so the broker can only ever make a build faster.
 */
final class JavacBrokerClient {
    private static final Map<JavacBrokerIdentity, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final String SESSION_ID = UUID.randomUUID().toString();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(JavacBrokerClient::endSessions, "zolt-javac-broker-goodbye"));
    }

    private JavacBrokerClient() {
    }

    static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("zolt.javac.worker.persistent", "true"));
    }

    static Optional<JavacRunner.ProcessResult> compile(
            Path javac,
            Path workerJar,
            int kind,
            List<String> arguments) {
        if (!enabled()) {
            return Optional.empty();
        }
        Optional<JavacBrokerConnection> connection = session(javac, workerJar).connection();
        if (connection.isEmpty()) {
            return Optional.empty();
        }
        try {
            return connection.orElseThrow().compile(kind, arguments);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Asks the broker to have {@code workers} children warm by the time the first compile lands. */
    static void prewarm(Path javac, Path workerJar, int workers) {
        if (!enabled() || workers < 1) {
            return;
        }
        session(javac, workerJar).connection().ifPresent(connection -> connection.prewarm(workers));
    }

    private static Session session(Path javac, Path workerJar) {
        return SESSIONS.computeIfAbsent(JavacBrokerIdentity.of(javac, workerJar), Session::new);
    }

    private static void endSessions() {
        SESSIONS.values().forEach(Session::end);
    }

    private static final class Session {
        private final JavacBrokerIdentity identity;
        private JavacBrokerConnection connection;
        private boolean unavailable;

        private Session(JavacBrokerIdentity identity) {
            this.identity = identity;
        }

        private synchronized Optional<JavacBrokerConnection> connection() {
            if (connection != null) {
                return Optional.of(connection);
            }
            if (unavailable) {
                return Optional.empty();
            }
            Optional<JavacBrokerConnection> opened = connect();
            if (opened.isEmpty()) {
                unavailable = true;
                return Optional.empty();
            }
            connection = opened.orElseThrow();
            return opened;
        }

        private Optional<JavacBrokerConnection> connect() {
            Path statePath;
            try {
                statePath = identity.statePath(JavacBrokerIdentity.runtimeDirectory());
            } catch (IOException exception) {
                return Optional.empty();
            }
            Optional<JavacBrokerLauncher.Metadata> existing = JavacBrokerLauncher.read(statePath);
            if (existing.isPresent()) {
                Optional<JavacBrokerConnection> reused = open(existing.orElseThrow());
                if (reused.isPresent()) {
                    return reused;
                }
            }
            return JavacBrokerLauncher.start(identity, statePath, existing.orElse(null))
                    .flatMap(Session::open);
        }

        private static Optional<JavacBrokerConnection> open(JavacBrokerLauncher.Metadata metadata) {
            return JavacBrokerConnection.open(metadata.port(), metadata.token(), SESSION_ID);
        }

        private synchronized void end() {
            if (connection != null) {
                connection.goodbye();
                connection = null;
            }
        }
    }
}
