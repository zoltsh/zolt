package sh.zolt.build.compile;

import sh.zolt.cancel.BuildCancellation;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One command's connection to the broker, carrying every member's compile over a single socket.
 *
 * <p>Requests are tagged and answered out of order by a reader thread, so the whole workspace shares
 * one connection instead of one per module. That single connection is also the safety mechanism:
 * while it is open the broker holds this command's children, and the moment it drops — a crash, a
 * kill, a cancelled build — the broker kills whatever those children were compiling. A command that
 * ends normally says goodbye first, which releases the children warm instead of killing them.
 */
final class JavacBrokerConnection implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MILLIS = 1_000;
    private static final long REQUEST_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    private final Socket socket;
    private final DataOutputStream requests;
    private final DataInputStream responses;
    private final Map<Long, Exchange> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextRequestId = new AtomicLong();

    private volatile boolean healthy = true;

    private JavacBrokerConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.requests = new DataOutputStream(socket.getOutputStream());
        this.responses = new DataInputStream(socket.getInputStream());
    }

    static Optional<JavacBrokerConnection> open(int port, String token, String sessionId) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MILLIS);
            JavacBrokerConnection connection = new JavacBrokerConnection(socket);
            if (!connection.hello(token, sessionId)) {
                connection.close();
                return Optional.empty();
            }
            connection.startReader();
            JavacWorkerMetrics.recordBrokerSession();
            return Optional.of(connection);
        } catch (IOException exception) {
            closeQuietly(socket);
            return Optional.empty();
        }
    }

    /**
     * Runs one compile on a broker-owned child. An empty result means "the broker could not do this",
     * which callers answer with a command-local worker; a cancelled request interrupts the caller
     * instead, because its work is no longer wanted.
     */
    Optional<JavacRunner.ProcessResult> compile(int kind, List<String> arguments)
            throws InterruptedException {
        if (!healthy) {
            return Optional.empty();
        }
        long requestId = nextRequestId.incrementAndGet();
        Exchange exchange = new Exchange();
        pending.put(requestId, exchange);
        try (BuildCancellation.Registration ignored = BuildCancellation.onCancel(() -> cancel(requestId))) {
            if (!send(requestId, kind, arguments)) {
                return Optional.empty();
            }
            return receive(requestId, exchange);
        } finally {
            pending.remove(requestId);
        }
    }

    void prewarm(int workers) {
        if (!healthy) {
            return;
        }
        try {
            synchronized (requests) {
                requests.writeInt(JavacBrokerWire.FRAME_PREWARM);
                requests.writeInt(workers);
                requests.flush();
            }
        } catch (IOException exception) {
            healthy = false;
        }
    }

    /** Ends the session cleanly, which is what lets the broker keep this command's children warm. */
    void goodbye() {
        if (healthy) {
            try {
                synchronized (requests) {
                    requests.writeInt(JavacBrokerWire.FRAME_GOODBYE);
                    requests.flush();
                }
            } catch (IOException ignored) {
                // The broker already treats a dropped connection as the end of the session.
            }
        }
        close();
    }

    @Override
    public void close() {
        healthy = false;
        closeQuietly(socket);
    }

    private boolean hello(String token, String sessionId) throws IOException {
        socket.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
        requests.writeInt(JavacWorkerWire.MAGIC);
        requests.writeInt(JavacBrokerWire.VERSION);
        JavacWorkerWire.writeString(requests, token);
        JavacWorkerWire.writeString(requests, sessionId);
        requests.flush();
        boolean accepted = responses.readInt() == JavacBrokerWire.HELLO_ACCEPT;
        socket.setSoTimeout(0);
        return accepted;
    }

    private void startReader() {
        Thread reader = new Thread(this::read, "zolt-javac-broker-client");
        reader.setDaemon(true);
        reader.start();
    }

    private void read() {
        try {
            while (true) {
                long requestId = responses.readLong();
                Response response = new Response(
                        responses.readInt(),
                        responses.readLong(),
                        responses.readLong(),
                        responses.readLong(),
                        responses.readInt() == 1,
                        readPayload());
                Exchange exchange = pending.get(requestId);
                if (exchange != null) {
                    exchange.complete(response);
                }
            }
        } catch (IOException exception) {
            healthy = false;
            failPending();
        }
    }

    private byte[] readPayload() throws IOException {
        int length = responses.readInt();
        if (length < 0) {
            throw new IOException("invalid broker payload length " + length);
        }
        byte[] payload = responses.readNBytes(length);
        if (payload.length != length) {
            throw new IOException("incomplete broker payload");
        }
        return payload;
    }

    private void failPending() {
        List<Exchange> waiting = new ArrayList<>(pending.values());
        waiting.forEach(exchange -> exchange.complete(Response.failed()));
    }

    private boolean send(long requestId, int kind, List<String> arguments) {
        try {
            synchronized (requests) {
                requests.writeInt(JavacBrokerWire.FRAME_REQUEST);
                requests.writeLong(requestId);
                JavacWorkerWire.writeRequest(requests, kind, arguments);
                requests.flush();
            }
            return true;
        } catch (IOException exception) {
            healthy = false;
            return false;
        }
    }

    private Optional<JavacRunner.ProcessResult> receive(long requestId, Exchange exchange)
            throws InterruptedException {
        Response response;
        try {
            response = exchange.await();
        } catch (InterruptedException exception) {
            cancel(requestId);
            throw exception;
        }
        if (response == null || response.status() == JavacBrokerWire.STATUS_FAILED) {
            return Optional.empty();
        }
        if (response.status() == JavacBrokerWire.STATUS_CANCELLED) {
            throw new InterruptedException("javac broker request was cancelled");
        }
        JavacWorkerMetrics.recordBrokerRequest(
                response.workerStarted(),
                response.startupNanos(),
                response.queueNanos(),
                response.requestNanos());
        try {
            return Optional.of(JavacWorkerWire.readResponse(
                    new DataInputStream(new ByteArrayInputStream(response.payload()))));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private void cancel(long requestId) {
        if (!healthy) {
            return;
        }
        try {
            synchronized (requests) {
                requests.writeInt(JavacBrokerWire.FRAME_CANCEL);
                requests.writeLong(requestId);
                requests.flush();
            }
        } catch (IOException exception) {
            healthy = false;
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing is best effort; the broker notices the dropped connection either way.
        }
    }

    private static final class Exchange {
        private final ArrayBlockingQueue<Response> slot = new ArrayBlockingQueue<>(1);

        private void complete(Response response) {
            slot.offer(response);
        }

        private Response await() throws InterruptedException {
            return slot.poll(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private record Response(
            int status,
            long queueNanos,
            long startupNanos,
            long requestNanos,
            boolean workerStarted,
            byte[] payload) {
        private static Response failed() {
            return new Response(JavacBrokerWire.STATUS_FAILED, 0L, 0L, 0L, false, new byte[0]);
        }
    }
}
