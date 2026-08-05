package sh.zolt.javac;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The persistent javac broker: a supervisor process that owns warm child worker JVMs and hands them
 * out, one request at a time, to whichever command is connected.
 *
 * <p>It deliberately runs no compiler itself. Compilation happens only in children, so the broker
 * can revoke a command's work by killing a process instead of trying to unwind a shared thread that
 * is halfway through writing class files. It shuts itself down once it has been idle, and its
 * children die with it.
 */
final class BrokerServer {
    private static final long DEFAULT_IDLE_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final int ACCEPT_POLL_MILLIS = 1_000;

    private BrokerServer() {
    }

    static int run(Path statePath, List<String> workerJvmArguments, PrintStream error) {
        return run(
                statePath.toAbsolutePath().normalize(),
                childCommand(workerJvmArguments),
                Long.getLong("zolt.javac.worker.idleTimeoutMillis", DEFAULT_IDLE_TIMEOUT_MILLIS),
                maximumWorkers(),
                error,
                () -> { });
    }

    /**
     * The supervisor decides what it supervises, so the child command is a parameter rather than
     * something baked into the pool.
     */
    static int run(
            Path statePath,
            List<String> childCommand,
            long idleTimeoutMillis,
            int maximumWorkers,
            PrintStream error,
            Runnable started) {
        String token = BrokerState.token();
        AtomicInteger sessions = new AtomicInteger();
        AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        WorkerChildPool pool = new WorkerChildPool(childCommand, maximumWorkers);
        ExecutorService connections = threadPool("zolt-javac-broker-session");
        ExecutorService requests = threadPool("zolt-javac-broker-request");
        Runtime.getRuntime().addShutdownHook(new Thread(pool::close, "zolt-javac-broker-shutdown"));
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            server.setSoTimeout(ACCEPT_POLL_MILLIS);
            BrokerState.write(statePath, server.getLocalPort(), token);
            started.run();
            accept(server, token, pool, sessions, lastActivity, connections, requests, idleTimeoutMillis);
            return 0;
        } catch (IOException exception) {
            error.println("error: Zolt javac broker failed: " + exception.getMessage());
            return 1;
        } finally {
            connections.shutdownNow();
            requests.shutdownNow();
            pool.close();
            BrokerState.deleteOwned(statePath, token);
        }
    }

    private static void accept(
            ServerSocket server,
            String token,
            WorkerChildPool pool,
            AtomicInteger sessions,
            AtomicLong lastActivity,
            ExecutorService connections,
            ExecutorService requests,
            long idleTimeoutMillis) throws IOException {
        while (!idle(sessions, lastActivity, idleTimeoutMillis)) {
            try {
                Socket socket = server.accept();
                sessions.incrementAndGet();
                lastActivity.set(System.nanoTime());
                connections.execute(new BrokerSession(socket, token, pool, requests, () -> {
                    lastActivity.set(System.nanoTime());
                    sessions.decrementAndGet();
                }));
            } catch (SocketTimeoutException ignored) {
                // Recheck the idle deadline.
            }
        }
    }

    private static boolean idle(AtomicInteger sessions, AtomicLong lastActivity, long idleTimeoutMillis) {
        return sessions.get() == 0
                && System.nanoTime() - lastActivity.get() >= TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis);
    }

    private static ExecutorService threadPool(String name) {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static int maximumWorkers() {
        Integer configured = Integer.getInteger("zolt.javac.worker.maxWorkers");
        if (configured != null && configured > 0) {
            return configured;
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private static List<String> childCommand(List<String> workerJvmArguments) {
        List<String> command = new ArrayList<>();
        command.add(childJava().toString());
        command.addAll(workerJvmArguments);
        command.add("-classpath");
        command.add(System.getProperty("java.class.path", ""));
        command.add(JavacWorkerMain.class.getName());
        command.add(JavacWorkerMain.FRAMED_FLAG);
        command.add(JavacWorkerMain.SUPERVISOR_PID_FLAG);
        command.add(Long.toString(ProcessHandle.current().pid()));
        return List.copyOf(command);
    }

    private static Path childJava() {
        String home = System.getProperty("java.home", "");
        String name = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        return Path.of(home).resolve("bin").resolve(name);
    }
}
