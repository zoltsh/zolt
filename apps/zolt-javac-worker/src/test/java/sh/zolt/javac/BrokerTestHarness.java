package sh.zolt.javac;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A broker running against {@link BlockingWorkerFixture} children, plus a client that drives it. */
final class BrokerTestHarness implements AutoCloseable {
    private static final long TIMEOUT_SECONDS = 30;

    private final ServerSocket control = new ServerSocket();
    private final List<Socket> children = new ArrayList<>();
    private final List<Session> sessions = new ArrayList<>();
    private final ArrayBlockingQueue<Long> announced = new ArrayBlockingQueue<>(64);
    private final AtomicInteger port = new AtomicInteger();
    private final Thread broker;
    private final Path statePath;

    BrokerTestHarness(Path directory, int maximumWorkers) throws Exception {
        this.statePath = directory.resolve("broker.state");
        control.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Thread announcements = new Thread(this::acceptChildren, "fixture-control");
        announcements.setDaemon(true);
        announcements.start();
        ArrayBlockingQueue<Boolean> ready = new ArrayBlockingQueue<>(1);
        broker = new Thread(
                () -> BrokerServer.run(
                        statePath,
                        childCommand(),
                        TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS),
                        maximumWorkers,
                        System.err,
                        () -> ready.offer(true)),
                "broker-under-test");
        broker.setDaemon(true);
        broker.start();
        if (ready.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS) == null) {
            throw new IllegalStateException("broker did not start");
        }
        port.set(Integer.parseInt(state().get("port")));
    }

    Map<String, String> state() throws IOException {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (String line : Files.readAllLines(statePath)) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return values;
    }

    Session connect() throws Exception {
        return connect(BrokerProtocol.VERSION, state().get("token"));
    }

    Session connect(int version, String token) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port.get()), 5_000);
        Session session = new Session(socket, version, token);
        sessions.add(session);
        return session;
    }

    /** The process id of the next child to report that it started a request. */
    long nextStartedChild() throws InterruptedException {
        Long pid = announced.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (pid == null) {
            throw new IllegalStateException("no child reported starting a request");
        }
        return pid;
    }

    /** Lets every child that is currently blocked finish its request. */
    void releaseChildren() {
        synchronized (children) {
            for (Socket child : children) {
                try {
                    child.getOutputStream().write(1);
                    child.getOutputStream().flush();
                } catch (IOException ignored) {
                    // A killed child has nothing left to release, which is the point of some tests.
                }
            }
        }
    }

    @Override
    public void close() throws Exception {
        for (Session session : sessions) {
            session.close();
        }
        synchronized (children) {
            for (Socket child : children) {
                child.close();
            }
        }
        control.close();
        broker.interrupt();
    }

    private void acceptChildren() {
        while (!control.isClosed()) {
            try {
                Socket child = control.accept();
                synchronized (children) {
                    children.add(child);
                }
                Thread reader = new Thread(() -> announce(child), "fixture-announcements");
                reader.setDaemon(true);
                reader.start();
            } catch (IOException exception) {
                return;
            }
        }
    }

    private void announce(Socket child) {
        try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                announced.offer(Long.parseLong(line.substring("started ".length()).trim()));
            }
        } catch (IOException | RuntimeException ignored) {
            // The child was killed, which several tests expect.
        }
    }

    private List<String> childCommand() {
        return List.of(
                javaExecutable(),
                "-classpath",
                classpathFor(BlockingWorkerFixture.class, JavacWorkerMain.class),
                BlockingWorkerFixture.class.getName(),
                Integer.toString(control.getLocalPort()));
    }

    static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * The classpath a child needs, taken from where the classes actually live rather than from
     * {@code java.class.path}, which a test runner does not have to populate.
     */
    static String classpathFor(Class<?>... classes) {
        List<String> entries = new ArrayList<>();
        for (Class<?> type : classes) {
            try {
                String entry = Path.of(
                        type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                if (!entries.contains(entry)) {
                    entries.add(entry);
                }
            } catch (java.net.URISyntaxException exception) {
                throw new IllegalStateException("could not locate " + type, exception);
            }
        }
        return String.join(java.io.File.pathSeparator, entries);
    }

    /** One CLI-side connection: handshake, multiplexed requests, cancel, goodbye. */
    static final class Session implements AutoCloseable {
        private final Socket socket;
        private final DataOutputStream requests;
        private final DataInputStream responses;
        private final boolean accepted;

        private Session(Socket socket, int version, String token) throws IOException {
            this.socket = socket;
            this.requests = new DataOutputStream(socket.getOutputStream());
            this.responses = new DataInputStream(socket.getInputStream());
            requests.writeInt(BrokerProtocol.MAGIC);
            requests.writeInt(version);
            WorkerCompileProtocol.writeString(requests, token);
            WorkerCompileProtocol.writeString(requests, "session-" + socket.getLocalPort());
            requests.flush();
            this.accepted = responses.readInt() == BrokerProtocol.HELLO_ACCEPT;
        }

        boolean accepted() {
            return accepted;
        }

        void request(long requestId, String argument) throws IOException {
            requests.writeInt(BrokerProtocol.FRAME_REQUEST);
            requests.writeLong(requestId);
            requests.writeInt(WorkerCompileProtocol.KIND_COMPILE);
            requests.writeInt(1);
            WorkerCompileProtocol.writeString(requests, argument);
            requests.flush();
        }

        void cancel(long requestId) throws IOException {
            requests.writeInt(BrokerProtocol.FRAME_CANCEL);
            requests.writeLong(requestId);
            requests.flush();
        }

        void prewarm(int workers) throws IOException {
            requests.writeInt(BrokerProtocol.FRAME_PREWARM);
            requests.writeInt(workers);
            requests.flush();
        }

        void goodbye() throws IOException {
            requests.writeInt(BrokerProtocol.FRAME_GOODBYE);
            requests.flush();
            socket.close();
        }

        Response response() throws IOException {
            long requestId = responses.readLong();
            int status = responses.readInt();
            long queueNanos = responses.readLong();
            long startupNanos = responses.readLong();
            long requestNanos = responses.readLong();
            boolean started = responses.readInt() == 1;
            byte[] payload = responses.readNBytes(responses.readInt());
            return new Response(requestId, status, queueNanos, startupNanos, requestNanos, started, payload);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    record Response(
            long requestId,
            int status,
            long queueNanos,
            long startupNanos,
            long requestNanos,
            boolean workerStarted,
            byte[] payload) {
    }
}
