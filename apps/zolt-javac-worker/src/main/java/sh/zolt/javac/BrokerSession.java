package sh.zolt.javac;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * One CLI command's multiplexed connection to the broker.
 *
 * <p>The session owns the leases it is holding, and nothing else. A cancel frame kills the child
 * running that one request; losing the connection kills every child the session still has in flight
 * and leaves the rest of the pool — including children other commands are using — untouched. A
 * command that finishes normally says goodbye first, so its children are released warm.
 */
final class BrokerSession implements Runnable {
    private final Socket socket;
    private final String token;
    private final WorkerChildPool pool;
    private final ExecutorService requests;
    private final Runnable finished;
    private final Map<Long, WorkerChildPool.Lease> inFlight = new ConcurrentHashMap<>();
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();

    private DataOutputStream responses;

    BrokerSession(
            Socket socket,
            String token,
            WorkerChildPool pool,
            ExecutorService requests,
            Runnable finished) {
        this.socket = socket;
        this.token = token;
        this.pool = pool;
        this.requests = requests;
        this.finished = finished;
    }

    @Override
    public void run() {
        boolean graceful = false;
        try (Socket open = socket;
                DataInputStream input = new DataInputStream(open.getInputStream());
                DataOutputStream output = new DataOutputStream(open.getOutputStream())) {
            responses = output;
            if (!accept(input, output)) {
                return;
            }
            graceful = serve(input);
        } catch (IOException ignored) {
            // A dropped connection is cancellation: the finally block kills the leases.
        } finally {
            if (!graceful) {
                abandonInFlight();
            }
            finished.run();
        }
    }

    private boolean accept(DataInputStream input, DataOutputStream output) throws IOException {
        boolean supported = input.readInt() == BrokerProtocol.MAGIC
                && input.readInt() == BrokerProtocol.VERSION;
        if (!supported) {
            output.writeInt(BrokerProtocol.HELLO_DECLINE);
            output.flush();
            return false;
        }
        if (!token.equals(WorkerCompileProtocol.readString(input))) {
            output.writeInt(BrokerProtocol.HELLO_DECLINE);
            output.flush();
            return false;
        }
        WorkerCompileProtocol.readString(input);
        output.writeInt(BrokerProtocol.HELLO_ACCEPT);
        output.flush();
        return true;
    }

    private boolean serve(DataInputStream input) throws IOException {
        while (true) {
            int frame = input.readInt();
            switch (frame) {
                case BrokerProtocol.FRAME_REQUEST -> submit(
                        input.readLong(),
                        WorkerCompileProtocol.readKind(input),
                        WorkerCompileProtocol.readArguments(input));
                case BrokerProtocol.FRAME_CANCEL -> cancel(input.readLong());
                case BrokerProtocol.FRAME_PREWARM -> pool.prewarm(
                        Math.min(Math.max(0, input.readInt()), BrokerProtocol.MAX_PREWARM));
                case BrokerProtocol.FRAME_GOODBYE -> {
                    return true;
                }
                default -> throw new IOException("invalid broker frame " + frame);
            }
        }
    }

    private void submit(long requestId, int kind, List<String> arguments) {
        try {
            requests.execute(() -> execute(requestId, kind, arguments));
        } catch (RuntimeException exception) {
            respondStatus(requestId, BrokerProtocol.STATUS_FAILED, 0L, null);
        }
    }

    private void execute(long requestId, int kind, List<String> arguments) {
        if (cancelled.contains(requestId)) {
            respondStatus(requestId, BrokerProtocol.STATUS_CANCELLED, 0L, null);
            return;
        }
        long queueStarted = System.nanoTime();
        WorkerChildPool.Lease lease;
        try {
            lease = pool.acquire();
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            respondStatus(requestId, BrokerProtocol.STATUS_FAILED, 0L, null);
            return;
        }
        long queueNanos = Math.max(0L, System.nanoTime() - queueStarted);
        inFlight.put(requestId, lease);
        if (cancelled.contains(requestId) && inFlight.remove(requestId) != null) {
            pool.discard(lease);
            respondStatus(requestId, BrokerProtocol.STATUS_CANCELLED, queueNanos, lease);
            return;
        }
        compile(requestId, kind, arguments, lease, queueNanos);
    }

    private void compile(
            long requestId,
            int kind,
            List<String> arguments,
            WorkerChildPool.Lease lease,
            long queueNanos) {
        long started = System.nanoTime();
        try {
            byte[] payload = lease.child().compile(kind, arguments);
            long requestNanos = Math.max(0L, System.nanoTime() - started);
            if (inFlight.remove(requestId) == null) {
                respondStatus(requestId, BrokerProtocol.STATUS_CANCELLED, queueNanos, lease);
                return;
            }
            pool.release(lease);
            respond(requestId, BrokerProtocol.STATUS_OK, queueNanos, requestNanos, lease, payload);
        } catch (IOException exception) {
            boolean stillHeld = inFlight.remove(requestId) != null;
            if (stillHeld) {
                pool.discard(lease);
            }
            respondStatus(
                    requestId,
                    cancelled.contains(requestId) ? BrokerProtocol.STATUS_CANCELLED : BrokerProtocol.STATUS_FAILED,
                    queueNanos,
                    lease);
        }
    }

    private void cancel(long requestId) {
        cancelled.add(requestId);
        WorkerChildPool.Lease lease = inFlight.remove(requestId);
        if (lease != null) {
            pool.discard(lease);
        }
    }

    private void abandonInFlight() {
        List<Long> abandoned = new ArrayList<>(inFlight.keySet());
        for (Long requestId : abandoned) {
            WorkerChildPool.Lease lease = inFlight.remove(requestId);
            if (lease != null) {
                pool.discard(lease);
            }
        }
    }

    private void respondStatus(long requestId, int status, long queueNanos, WorkerChildPool.Lease lease) {
        respond(requestId, status, queueNanos, 0L, lease, new byte[0]);
    }

    private void respond(
            long requestId,
            int status,
            long queueNanos,
            long requestNanos,
            WorkerChildPool.Lease lease,
            byte[] payload) {
        DataOutputStream output = responses;
        if (output == null) {
            return;
        }
        synchronized (output) {
            try {
                output.writeLong(requestId);
                output.writeInt(status);
                output.writeLong(queueNanos);
                output.writeLong(lease == null ? 0L : lease.startupNanos());
                output.writeLong(requestNanos);
                output.writeInt(lease != null && lease.started() ? 1 : 0);
                output.writeInt(payload.length);
                output.write(payload);
                output.flush();
            } catch (IOException ignored) {
                // The client is gone; the session's finally block kills its leases.
            }
        }
    }
}
