package sh.zolt.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The safety property the previous in-server design could not offer: a cancelled or disconnected
 * command stops compiling, provably, because its child process is gone.
 */
final class BrokerCancellationTest {
    @TempDir
    Path tempDir;

    @Test
    void cancelKillsOnlyTheChildLeasedToThatRequest() throws Exception {
        try (BrokerTestHarness harness = new BrokerTestHarness(tempDir, 4)) {
            BrokerTestHarness.Session first = harness.connect();
            BrokerTestHarness.Session second = harness.connect();
            first.request(1L, "first");
            long cancelled = harness.nextStartedChild();
            second.request(1L, "second");
            long survivor = harness.nextStartedChild();

            first.cancel(1L);

            assertTrue(exited(cancelled), "the cancelled request's child should be killed");
            assertTrue(alive(survivor), "a concurrent session's child must be untouched");
            assertEquals(BrokerProtocol.STATUS_CANCELLED, first.response().status());

            harness.releaseChildren();
            BrokerTestHarness.Response completed = second.response();
            assertEquals(BrokerProtocol.STATUS_OK, completed.status());
        }
    }

    @Test
    void losingTheConnectionKillsThatSessionsInFlightChildren() throws Exception {
        try (BrokerTestHarness harness = new BrokerTestHarness(tempDir, 4)) {
            BrokerTestHarness.Session abandoned = harness.connect();
            BrokerTestHarness.Session surviving = harness.connect();
            abandoned.request(7L, "abandoned");
            long doomed = harness.nextStartedChild();
            surviving.request(7L, "surviving");
            long survivor = harness.nextStartedChild();

            abandoned.close();

            assertTrue(exited(doomed), "a dropped connection must kill the work it was still doing");
            assertTrue(alive(survivor), "another command's child must survive the disconnect");
        }
    }

    @Test
    void aGoodbyeKeepsChildrenWarmForTheNextCommand() throws Exception {
        try (BrokerTestHarness harness = new BrokerTestHarness(tempDir, 4)) {
            BrokerTestHarness.Session first = harness.connect();
            first.request(1L, "first");
            long warm = harness.nextStartedChild();
            harness.releaseChildren();
            assertTrue(first.response().workerStarted());
            first.goodbye();

            BrokerTestHarness.Session second = harness.connect();
            second.request(1L, "second");
            long reused = harness.nextStartedChild();
            harness.releaseChildren();
            BrokerTestHarness.Response response = second.response();

            assertEquals(warm, reused, "the second command should land on the first command's child");
            assertFalse(response.workerStarted(), "a reused child is not a start");
            assertEquals(BrokerProtocol.STATUS_OK, response.status());
        }
    }

    private static boolean exited(long pid) throws Exception {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            return true;
        }
        try {
            handle.orElseThrow().onExit().get(30, TimeUnit.SECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException exception) {
            return false;
        }
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
