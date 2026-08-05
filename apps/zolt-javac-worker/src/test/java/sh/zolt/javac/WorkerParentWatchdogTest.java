package sh.zolt.javac;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * The property that closes the broker-crash double-writer window: a child worker whose supervisor
 * dies halts on its own instead of running its in-flight work to completion.
 *
 * <p>The children here are real {@link JavacWorkerMain} JVMs blocked reading their first request with
 * stdin held open by this test, so neither an EOF nor a finished compile can end them — the watchdog
 * is the only thing that can. That isolates exactly what the fix adds; the broker-owned cancel path
 * (a live broker killing its own child) is covered unchanged by {@link BrokerCancellationTest}.
 */
final class WorkerParentWatchdogTest {
    private static final long EXIT_TIMEOUT_SECONDS = 30;
    private static final long STAY_ALIVE_SECONDS = 2;

    @Test
    void aChildHaltsWhenItsSupervisorDies() throws Exception {
        Process supervisor = startWorker(null);
        try {
            Process child = startWorker(supervisor.pid());
            try {
                supervisor.destroyForcibly();

                assertTrue(
                        child.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "a child must halt once the supervisor that launched it dies");
            } finally {
                child.destroyForcibly();
            }
        } finally {
            supervisor.destroyForcibly();
        }
    }

    @Test
    void aChildKeepsRunningWhileItsSupervisorLives() throws Exception {
        // This test JVM stands in for a living supervisor, so nothing should make the child halt.
        Process child = startWorker(ProcessHandle.current().pid());
        try {
            assertThrows(
                    TimeoutException.class,
                    () -> child.onExit().get(STAY_ALIVE_SECONDS, TimeUnit.SECONDS),
                    "a child must keep running while its supervisor is alive");
        } finally {
            child.destroyForcibly();
        }
    }

    /** A real worker JVM, blocked reading its first request, watching {@code supervisorPid} if given. */
    private static Process startWorker(Long supervisorPid) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                BrokerTestHarness.javaExecutable(),
                "-classpath",
                BrokerTestHarness.classpathFor(JavacWorkerMain.class),
                JavacWorkerMain.class.getName(),
                JavacWorkerMain.FRAMED_FLAG));
        if (supervisorPid != null) {
            command.add(JavacWorkerMain.SUPERVISOR_PID_FLAG);
            command.add(Long.toString(supervisorPid));
        }
        return new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
