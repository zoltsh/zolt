package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cancel.BuildCancellation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class JavacBrokerConnectionTest {
    @BeforeEach
    void resetMetrics() {
        JavacWorkerMetrics.reset();
    }

    @Test
    void matchesResponsesToRequestsWhateverOrderTheyArriveIn() throws Exception {
        try (FakeJavacBroker broker = new FakeJavacBroker();
                JavacBrokerConnection connection = open(broker)) {
            Map<String, String> outputs = new ConcurrentHashMap<>();
            CountDownLatch completed = new CountDownLatch(2);
            compileAsync(connection, "first", outputs, completed);
            FakeJavacBroker.Frame first = broker.takeFrame();
            compileAsync(connection, "second", outputs, completed);
            FakeJavacBroker.Frame second = broker.takeFrame();
            assertEquals(List.of("first"), first.arguments());
            assertEquals(List.of("second"), second.arguments());

            broker.respond(second.requestId(), JavacBrokerWire.STATUS_OK, true, "second done");
            broker.respond(first.requestId(), JavacBrokerWire.STATUS_OK, false, "first done");

            assertTrue(completed.await(30, TimeUnit.SECONDS));
            assertEquals(Map.of("first", "first done", "second", "second done"), outputs);
            assertEquals(1L, JavacWorkerMetrics.snapshot().starts());
            assertEquals(1L, JavacWorkerMetrics.snapshot().reuses());
        }
    }

    @Test
    void sendsACancelFrameWhenTheBuildTaskIsCancelled() throws Exception {
        try (FakeJavacBroker broker = new FakeJavacBroker();
                JavacBrokerConnection connection = open(broker)) {
            BuildCancellation cancellation = new BuildCancellation();
            ArrayBlockingQueue<Object> outcome = new ArrayBlockingQueue<>(1);
            Thread task = new Thread(() -> cancellation.call(() -> {
                try {
                    connection.compile(JavacWorkerWire.KIND_COMPILE, List.of("slow"));
                    outcome.offer("completed");
                } catch (InterruptedException exception) {
                    outcome.offer("interrupted");
                }
                return null;
            }));
            task.start();
            FakeJavacBroker.Frame request = broker.takeFrame();

            cancellation.cancel();

            FakeJavacBroker.Frame cancel = broker.takeFrame();
            assertEquals(JavacBrokerWire.FRAME_CANCEL, cancel.type());
            assertEquals(request.requestId(), cancel.requestId());

            broker.respond(request.requestId(), JavacBrokerWire.STATUS_CANCELLED, false, "");
            assertEquals("interrupted", outcome.poll(30, TimeUnit.SECONDS));
            assertEquals(0L, JavacWorkerMetrics.snapshot().brokerRequests());
        }
    }

    @Test
    void reportsAFailedRequestAsNoAnswerSoTheCallerCanFallBack() throws Exception {
        try (FakeJavacBroker broker = new FakeJavacBroker();
                JavacBrokerConnection connection = open(broker)) {
            ArrayBlockingQueue<Optional<JavacRunner.ProcessResult>> results = new ArrayBlockingQueue<>(1);
            Thread task = new Thread(() -> {
                try {
                    results.offer(connection.compile(JavacWorkerWire.KIND_COMPILE, List.of("doomed")));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            task.start();
            FakeJavacBroker.Frame request = broker.takeFrame();

            broker.respond(request.requestId(), JavacBrokerWire.STATUS_FAILED, false, "");

            assertTrue(results.poll(30, TimeUnit.SECONDS).isEmpty());
        }
    }

    @Test
    void treatsALostBrokerAsNoAnswerRatherThanAFailure() throws Exception {
        FakeJavacBroker broker = new FakeJavacBroker();
        try (JavacBrokerConnection connection = open(broker)) {
            ArrayBlockingQueue<Optional<JavacRunner.ProcessResult>> results = new ArrayBlockingQueue<>(1);
            Thread task = new Thread(() -> {
                try {
                    results.offer(connection.compile(JavacWorkerWire.KIND_COMPILE, List.of("orphan")));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            task.start();
            broker.takeFrame();

            broker.close();

            assertTrue(results.poll(30, TimeUnit.SECONDS).isEmpty());
        }
    }

    @Test
    void declinedHandshakesProduceNoConnection() throws Exception {
        try (FakeJavacBroker broker = new FakeJavacBroker(JavacBrokerWire.HELLO_DECLINE)) {
            assertTrue(
                    JavacBrokerConnection.open(broker.port(), FakeJavacBroker.TOKEN, "session").isEmpty(),
                    "a broker speaking another protocol must not be used");
        }
    }

    @Test
    void goodbyeIsSentBeforeTheConnectionCloses() throws Exception {
        try (FakeJavacBroker broker = new FakeJavacBroker()) {
            JavacBrokerConnection connection = open(broker);

            connection.goodbye();

            assertEquals(JavacBrokerWire.FRAME_GOODBYE, broker.takeFrame().type());
        }
    }

    @Test
    void prewarmAsksForWorkersAheadOfTheFirstCompile() throws Exception {
        try (FakeJavacBroker broker = new FakeJavacBroker();
                JavacBrokerConnection connection = open(broker)) {
            connection.prewarm(6);

            FakeJavacBroker.Frame frame = broker.takeFrame();
            assertEquals(JavacBrokerWire.FRAME_PREWARM, frame.type());
            assertEquals(6L, frame.requestId());
        }
    }

    private static JavacBrokerConnection open(FakeJavacBroker broker) {
        return JavacBrokerConnection.open(broker.port(), FakeJavacBroker.TOKEN, "session")
                .orElseThrow(() -> new IllegalStateException("the fake broker refused the handshake"));
    }

    private static void compileAsync(
            JavacBrokerConnection connection,
            String argument,
            Map<String, String> outputs,
            CountDownLatch completed) {
        Thread thread = new Thread(() -> {
            try {
                connection.compile(JavacWorkerWire.KIND_COMPILE, List.of(argument))
                        .ifPresent(result -> outputs.put(argument, result.output()));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                completed.countDown();
            }
        });
        thread.setDaemon(true);
        thread.start();
    }
}
