package sh.zolt.javac;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One child worker JVM owned by the broker, speaking the framed stdin/stdout worker protocol.
 *
 * <p>The child is the unit of cancellation: {@link #destroy()} kills the process, which is the only
 * way to guarantee that a cancelled compile stops writing class files. A child is reused across
 * requests and across commands, so its JIT profile and file-manager JAR indexes stay warm.
 */
final class WorkerChild {
    private static final int DESTROY_TIMEOUT_MILLIS = 500;

    private final Process process;
    private final DataOutputStream requests;
    private final DataInputStream responses;
    private final long startupNanos;

    private WorkerChild(Process process, long startupNanos) {
        this.process = process;
        this.startupNanos = startupNanos;
        this.requests = new DataOutputStream(process.getOutputStream());
        this.responses = new DataInputStream(process.getInputStream());
    }

    static WorkerChild start(List<String> command) throws IOException {
        long started = System.nanoTime();
        Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        return new WorkerChild(process, Math.max(0L, System.nanoTime() - started));
    }

    /** Runs one compile and returns the child's response bytes verbatim, ready to relay. */
    byte[] compile(int kind, List<String> arguments) throws IOException {
        requests.writeInt(kind);
        requests.writeInt(arguments.size());
        for (String argument : arguments) {
            WorkerCompileProtocol.writeString(requests, argument);
        }
        requests.flush();
        return WorkerCompileProtocol.readFramedResponse(responses);
    }

    long startupNanos() {
        return startupNanos;
    }

    boolean isAlive() {
        return process.isAlive();
    }

    long pid() {
        return process.pid();
    }

    void destroy() {
        process.destroy();
        try {
            if (!process.waitFor(DESTROY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
