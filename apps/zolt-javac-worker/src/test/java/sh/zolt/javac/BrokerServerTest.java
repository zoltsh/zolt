package sh.zolt.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BrokerServerTest {
    @TempDir
    Path tempDir;

    @Test
    void publishesTheProtocolItSpeaksAndAcceptsThatVersion() throws Exception {
        try (BrokerTestHarness harness = new BrokerTestHarness(tempDir, 2)) {
            assertEquals(
                    Integer.toString(BrokerProtocol.VERSION),
                    harness.state().get("version"));
            assertTrue(harness.connect().accepted());
        }
    }

    @Test
    void declinesAClientSpeakingAnotherProtocolVersion() throws Exception {
        try (BrokerTestHarness harness = new BrokerTestHarness(tempDir, 2)) {
            String token = harness.state().get("token");
            assertFalse(harness.connect(BrokerProtocol.VERSION + 1, token).accepted());
            assertTrue(harness.connect().accepted(), "the broker keeps serving current clients");
        }
    }

    @Test
    void declinesAClientWithoutTheRendezvousToken() throws Exception {
        try (BrokerTestHarness harness = new BrokerTestHarness(tempDir, 2)) {
            assertFalse(harness.connect(BrokerProtocol.VERSION, "not-the-token").accepted());
        }
    }

    @Test
    void relaysTheChildResponseVerbatim() throws Exception {
        try (BrokerTestHarness harness = new BrokerTestHarness(tempDir, 2)) {
            BrokerTestHarness.Session session = harness.connect();
            session.request(42L, "-d");
            harness.nextStartedChild();
            harness.releaseChildren();

            BrokerTestHarness.Response response = session.response();

            assertEquals(42L, response.requestId());
            assertEquals(BrokerProtocol.STATUS_OK, response.status());
            assertTrue(
                    new String(response.payload(), StandardCharsets.UTF_8).contains("fixture compiled"),
                    "the broker must not reinterpret what the child produced");
        }
    }

    @Test
    void acceptsAPrewarmFrameAheadOfRequests() throws Exception {
        try (BrokerTestHarness harness = new BrokerTestHarness(tempDir, 2)) {
            BrokerTestHarness.Session session = harness.connect();
            session.prewarm(2);
            session.request(1L, "first");
            harness.nextStartedChild();
            harness.releaseChildren();

            assertEquals(BrokerProtocol.STATUS_OK, session.response().status());
        }
    }

    @Test
    void servesConcurrentRequestsOnDistinctChildren() throws Exception {
        try (BrokerTestHarness harness = new BrokerTestHarness(tempDir, 4)) {
            BrokerTestHarness.Session session = harness.connect();
            session.request(1L, "one");
            long first = harness.nextStartedChild();
            session.request(2L, "two");
            long second = harness.nextStartedChild();

            assertFalse(first == second, "one compilation per worker at a time");

            harness.releaseChildren();
            assertEquals(BrokerProtocol.STATUS_OK, session.response().status());
            assertEquals(BrokerProtocol.STATUS_OK, session.response().status());
        }
    }
}
