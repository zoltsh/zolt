package sh.zolt.build.compile;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A scripted broker, so the client half can be tested without launching worker JVMs. */
final class FakeJavacBroker implements AutoCloseable {
    static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final ServerSocket server = new ServerSocket();
    private final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(64);
    private final int helloReply;
    private final Thread acceptor;

    private volatile DataOutputStream responses;
    private volatile String sessionId;
    private volatile Socket client;

    FakeJavacBroker() throws IOException {
        this(JavacBrokerWire.HELLO_ACCEPT);
    }

    FakeJavacBroker(int helloReply) throws IOException {
        this.helloReply = helloReply;
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        acceptor = new Thread(this::serve, "fake-javac-broker");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    String sessionId() {
        return sessionId;
    }

    Frame takeFrame() throws InterruptedException {
        Frame frame = frames.poll(30, TimeUnit.SECONDS);
        if (frame == null) {
            throw new IllegalStateException("no client frame arrived");
        }
        return frame;
    }

    void respond(long requestId, int status, boolean workerStarted, String diagnostics) throws IOException {
        byte[] payload = status == JavacBrokerWire.STATUS_OK ? payload(diagnostics) : new byte[0];
        DataOutputStream output = responses;
        synchronized (output) {
            output.writeLong(requestId);
            output.writeInt(status);
            output.writeLong(11L);
            output.writeLong(13L);
            output.writeLong(17L);
            output.writeInt(workerStarted ? 1 : 0);
            output.writeInt(payload.length);
            output.write(payload);
            output.flush();
        }
    }

    @Override
    public void close() throws IOException {
        Socket connected = client;
        if (connected != null) {
            connected.close();
        }
        server.close();
        acceptor.interrupt();
    }

    private static byte[] payload(String diagnostics) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(buffer);
        body.writeInt(0);
        byte[] bytes = diagnostics.getBytes(StandardCharsets.UTF_8);
        body.writeInt(bytes.length);
        body.write(bytes);
        body.writeInt(0);
        return buffer.toByteArray();
    }

    private void serve() {
        try (Socket socket = server.accept();
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
            client = socket;
            responses = output;
            input.readInt();
            input.readInt();
            JavacWorkerWire.readString(input);
            sessionId = JavacWorkerWire.readString(input);
            output.writeInt(helloReply);
            output.flush();
            if (helloReply != JavacBrokerWire.HELLO_ACCEPT) {
                return;
            }
            read(input);
        } catch (IOException ignored) {
            // The client closed, which ends the scripted session.
        }
    }

    private void read(DataInputStream input) throws IOException {
        while (true) {
            int type = input.readInt();
            if (type == JavacBrokerWire.FRAME_PREWARM) {
                frames.offer(new Frame(type, input.readInt(), 0, List.of()));
                continue;
            }
            if (type == JavacBrokerWire.FRAME_GOODBYE) {
                frames.offer(new Frame(type, 0L, 0, List.of()));
                return;
            }
            long requestId = input.readLong();
            if (type == JavacBrokerWire.FRAME_CANCEL) {
                frames.offer(new Frame(type, requestId, 0, List.of()));
                continue;
            }
            int kind = input.readInt();
            int argumentCount = input.readInt();
            List<String> arguments = new ArrayList<>(argumentCount);
            for (int index = 0; index < argumentCount; index++) {
                arguments.add(JavacWorkerWire.readString(input));
            }
            frames.offer(new Frame(type, requestId, kind, arguments));
        }
    }

    record Frame(int type, long requestId, int kind, List<String> arguments) {
    }
}
