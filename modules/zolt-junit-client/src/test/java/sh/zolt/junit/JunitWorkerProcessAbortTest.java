package sh.zolt.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class JunitWorkerProcessAbortTest {
    @Test
    void abortTerminatesWithoutSendingQuitAndIsIdempotent() {
        StringWriter input = new StringWriter();
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger aborts = new AtomicInteger();
        JunitWorkerProcess process = new JunitWorkerProcess(
                new JunitWorkerClient(
                        new StringReader(""),
                        input),
                new JunitWorkerProcess.ProcessCloser() {
                    @Override
                    public void close() {
                        closes.incrementAndGet();
                    }

                    @Override
                    public void abort() {
                        aborts.incrementAndGet();
                    }
                });

        process.abort();
        process.abort();

        assertEquals(1, aborts.get());
        assertEquals(0, closes.get());
        assertEquals("", input.toString());
        JunitWorkerClientException exception = assertThrows(
                JunitWorkerClientException.class,
                () -> process.run(
                        Path.of("/repo/target/test-classes")));
        assertTrue(exception.getMessage().contains(
                "process is already closed"));
    }
}
