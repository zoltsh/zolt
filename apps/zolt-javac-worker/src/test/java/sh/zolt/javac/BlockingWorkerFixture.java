package sh.zolt.javac;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A child worker the test drives instead of javac, so cancellation can be observed at an exact
 * instant rather than guessed at with sleeps.
 *
 * <p>It speaks the framed child protocol on stdin/stdout and reports to a control socket: after
 * reading a request it announces its own process id, then blocks on the control socket until the test
 * releases it. A test therefore knows precisely when a compile is in flight, and a child that never
 * gets released stays in flight until something kills it — which is the property under test.
 */
public final class BlockingWorkerFixture {
    private BlockingWorkerFixture() {
    }

    public static void main(String[] args) throws Exception {
        int controlPort = Integer.parseInt(args[0]);
        DataInputStream requests = new DataInputStream(System.in);
        DataOutputStream responses = new DataOutputStream(System.out);
        try (Socket control = new Socket(InetAddress.getLoopbackAddress(), controlPort)) {
            OutputStream announcements = control.getOutputStream();
            while (true) {
                if (!readRequest(requests)) {
                    return;
                }
                announcements.write(
                        ("started " + ProcessHandle.current().pid() + "\n").getBytes(StandardCharsets.UTF_8));
                announcements.flush();
                if (control.getInputStream().read() < 0) {
                    return;
                }
                respond(responses);
            }
        }
    }

    private static boolean readRequest(DataInputStream requests) throws Exception {
        try {
            requests.readInt();
            int argumentCount = requests.readInt();
            for (int index = 0; index < argumentCount; index++) {
                requests.readNBytes(requests.readInt());
            }
            return true;
        } catch (EOFException exception) {
            return false;
        }
    }

    private static void respond(DataOutputStream responses) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(buffer);
        body.writeInt(0);
        byte[] diagnostics = "fixture compiled".getBytes(StandardCharsets.UTF_8);
        body.writeInt(diagnostics.length);
        body.write(diagnostics);
        body.writeInt(0);
        byte[] payload = buffer.toByteArray();
        responses.writeInt(payload.length);
        responses.write(payload);
        responses.flush();
    }
}
