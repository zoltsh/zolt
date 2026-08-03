package sh.zolt.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.test.TestSelection;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class JunitWorkerClientTest {
    @Test
    void requiresReaderAndWriter() {
        IllegalArgumentException missingOutput = assertThrows(
                IllegalArgumentException.class,
                () -> new JunitWorkerClient(null, new StringWriter()));
        IllegalArgumentException missingInput = assertThrows(
                IllegalArgumentException.class,
                () -> new JunitWorkerClient(new StringReader(""), null));

        assertTrue(missingOutput.getMessage().contains("output reader is required"));
        assertTrue(missingInput.getMessage().contains("input writer is required"));
    }

    @Test
    void sendsRunRequestAndPreservesOutputBeforeResult() {
        StringWriter input = new StringWriter();
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader("Tests found: 1\nTests succeeded: 1\nZOLT_WORKER_RESULT\tid=junit-1\texit=0\n"),
                input);

        JunitWorkerClient.WorkerRunResult result = client.run(Path.of("target/test-classes"));

        assertEquals("RUN\tv=1\tid=junit-1\tout=target/test-classes\n", input.toString());
        assertEquals("Tests found: 1\nTests succeeded: 1\n", result.output());
        assertEquals(0, result.exitCode());
        assertFalse(result.retireWorker());
    }

    @Test
    void reportsWhenTheWorkerMustRetireAfterARequest() {
        StringWriter input = new StringWriter();
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader(
                        "ZOLT_WORKER_RESULT\tid=junit-1\texit=0\tretire=true\n"),
                input);

        JunitWorkerClient.WorkerRunResult result =
                client.run(Path.of("target/test-classes"));

        assertTrue(result.retireWorker());
    }

    @Test
    void usesDeterministicRequestIdsAcrossMultipleRunsAndClose() {
        StringWriter input = new StringWriter();
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader("""
                        first run
                        ZOLT_WORKER_RESULT\tid=junit-1\texit=0
                        second run
                        ZOLT_WORKER_RESULT\tid=junit-2\texit=1
                        ZOLT_WORKER_RESULT\tid=junit-3\texit=0
                        """),
                input);

        JunitWorkerClient.WorkerRunResult first = client.run(Path.of("target/test-classes"));
        JunitWorkerClient.WorkerRunResult second = client.run(Path.of("target/test-classes"));
        client.close();

        assertEquals("""
                RUN\tv=1\tid=junit-1\tout=target/test-classes
                RUN\tv=1\tid=junit-2\tout=target/test-classes
                QUIT\tv=1\tid=junit-3
                """, input.toString());
        assertEquals("first run\n", first.output());
        assertEquals(0, first.exitCode());
        assertEquals("second run\n", second.output());
        assertEquals(1, second.exitCode());
    }

    @Test
    void closeSendsQuitRequest() {
        StringWriter input = new StringWriter();
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader("ZOLT_WORKER_RESULT\tid=junit-1\texit=0\n"),
                input);

        client.close();

        assertEquals("QUIT\tv=1\tid=junit-1\n", input.toString());
    }

    @Test
    void sendsRunRequestWithReportsAndEvents() {
        StringWriter input = new StringWriter();
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader("Tests found: 1\nZOLT_WORKER_RESULT\tid=junit-1\texit=0\n"),
                input);

        JunitWorkerClient.WorkerRunResult result = client.run(
                Path.of("target/test-classes"),
                TestSelection.empty(),
                Optional.of(Path.of("target/test-reports")),
                List.of("failed"));

        assertEquals("""
                RUN\tv=1\tid=junit-1\tout=target/test-classes\treports=target/test-reports\tevents=failed
                """, input.toString());
        assertEquals("Tests found: 1\n", result.output());
        assertEquals(0, result.exitCode());
    }

    @Test
    void sendsAnAbsoluteProjectDirectoryForDynamicRequests() {
        StringWriter input = new StringWriter();
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader(
                        "ZOLT_WORKER_RESULT\tid=junit-1\texit=0\n"),
                input);
        Path projectDirectory = Path.of("modules/demo");

        client.run(
                projectDirectory,
                List.of(Path.of("target/classes")),
                Path.of("target/test-classes"),
                TestSelection.empty(),
                Optional.empty(),
                List.of(),
                Optional.empty());

        JunitWorkerProtocol.WorkerRequest request =
                JunitWorkerProtocol.parseRequest(
                        input.toString().strip());
        assertEquals(
                Optional.of(projectDirectory
                        .toAbsolutePath()
                        .normalize()
                        .toString()),
                request.projectDirectory());
    }

    @Test
    void sendsRunRequestWithProfileDirectory() {
        StringWriter input = new StringWriter();
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader("ZOLT_WORKER_RESULT\tid=junit-1\texit=0\n"),
                input);

        JunitWorkerClient.WorkerRunResult result = client.run(
                Path.of("target/test-classes"),
                TestSelection.empty(),
                Optional.empty(),
                List.of(),
                Optional.of(Path.of("target/test-profile")));

        assertEquals("""
                RUN\tv=1\tid=junit-1\tout=target/test-classes\tprofile=target/test-profile
                """, input.toString());
        assertEquals("", result.output());
        assertEquals(0, result.exitCode());
    }

    @Test
    void sendsRunRequestWithSelectionFields() {
        StringWriter input = new StringWriter();
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader("ZOLT_WORKER_RESULT\tid=junit-1\texit=0\n"),
                input);
        TestSelection selection = TestSelection.fromFields(
                List.of("com.example.MainTest"),
                List.of(new TestSelection.MethodSelector("com.example.OtherTest", "runs")),
                List.of("*IntegrationTest"),
                List.of("fast"),
                List.of("slow"));

        JunitWorkerClient.WorkerRunResult result = client.run(
                Path.of("target/test-classes"),
                selection,
                Optional.empty(),
                List.of());

        assertEquals("""
                RUN\tv=1\tid=junit-1\tout=target/test-classes\tclasses=com.example.MainTest\tmethods=com.example.OtherTest#runs\tpatterns=*IntegrationTest\tincludeTags=fast\texcludeTags=slow
                """, input.toString());
        assertEquals("", result.output());
        assertEquals(0, result.exitCode());
    }

    @Test
    void closeIsIdempotent() {
        StringWriter input = new StringWriter();
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader("ZOLT_WORKER_RESULT\tid=junit-1\texit=0\n"),
                input);

        client.close();
        client.close();

        assertEquals("QUIT\tv=1\tid=junit-1\n", input.toString());
    }

    @Test
    void failsWhenQuitIsRejected() {
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader("ZOLT_WORKER_RESULT\tid=junit-1\texit=2\n"),
                new StringWriter());

        JunitWorkerClientException exception = assertThrows(
                JunitWorkerClientException.class,
                client::close);

        assertTrue(exception.getMessage().contains("rejected quit request"), exception.getMessage());
        assertTrue(exception.getMessage().contains("exit code 2"), exception.getMessage());
    }

    @Test
    void rejectsRunAfterClose() {
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader("ZOLT_WORKER_RESULT\tid=junit-1\texit=0\n"),
                new StringWriter());

        client.close();
        JunitWorkerClientException exception = assertThrows(
                JunitWorkerClientException.class,
                () -> client.run(Path.of("target/test-classes")));

        assertTrue(exception.getMessage().contains("already closed"));
    }

    @Test
    void failsWhenWorkerExitsBeforeResult() {
        StringWriter input = new StringWriter();
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader("Tests found: 1\n"),
                input);

        JunitWorkerClientException exception = assertThrows(
                JunitWorkerClientException.class,
                () -> client.run(Path.of("target/test-classes")));

        assertEquals("RUN\tv=1\tid=junit-1\tout=target/test-classes\n", input.toString());
        assertTrue(exception.getMessage().contains("exited before sending a result"));
        assertTrue(exception.getMessage().contains("junit-1"));
        assertTrue(exception.getMessage().contains("Tests found: 1"));
    }

    @Test
    void failsWhenWorkerExitsBeforeResultWithoutOutput() {
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader(""),
                new StringWriter());

        JunitWorkerClientException exception = assertThrows(
                JunitWorkerClientException.class,
                () -> client.run(Path.of("target/test-classes")));

        assertEquals("JUnit worker exited before sending a result for request `junit-1`.", exception.getMessage());
    }

    @Test
    void failsOnMismatchedResultRequestId() {
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader("ZOLT_WORKER_RESULT\tid=junit-2\texit=0\n"),
                new StringWriter());

        JunitWorkerClientException exception = assertThrows(
                JunitWorkerClientException.class,
                () -> client.run(Path.of("target/test-classes")));

        assertTrue(exception.getMessage().contains("junit-2"));
        assertTrue(exception.getMessage().contains("junit-1"));
    }

    @Test
    void wrapsMalformedWorkerResult() {
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader("ZOLT_WORKER_RESULT\tid=junit-1\texit=nope\n"),
                new StringWriter());

        JunitWorkerClientException exception = assertThrows(
                JunitWorkerClientException.class,
                () -> client.run(Path.of("target/test-classes")));

        assertTrue(exception.getMessage().contains("Malformed JUnit worker result exit code"), exception.getMessage());
    }

    @Test
    void closeWrapsMalformedQuitResult() {
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader("ZOLT_WORKER_RESULT\tid=junit-1\texit=nope\n"),
                new StringWriter());

        JunitWorkerClientException exception = assertThrows(
                JunitWorkerClientException.class,
                client::close);

        assertTrue(exception.getMessage().contains("Malformed JUnit worker result exit code"), exception.getMessage());
    }

    @Test
    void wrapsWorkerOutputReadFailures() {
        JunitWorkerClient client = new JunitWorkerClient(
                new FailingReader(),
                new StringWriter());

        JunitWorkerClientException exception = assertThrows(
                JunitWorkerClientException.class,
                () -> client.run(Path.of("target/test-classes")));

        assertTrue(exception.getMessage().contains("Could not read JUnit worker output"), exception.getMessage());
        assertTrue(exception.getCause() instanceof IOException);
    }

    @Test
    void wrapsWorkerRequestWriteFailures() {
        JunitWorkerClient client = new JunitWorkerClient(
                new StringReader(""),
                new FailingWriter());

        JunitWorkerClientException exception = assertThrows(
                JunitWorkerClientException.class,
                () -> client.run(Path.of("target/test-classes")));

        assertTrue(exception.getMessage().contains("Could not write JUnit worker request"), exception.getMessage());
        assertTrue(exception.getCause() instanceof IOException);
    }

    private static final class FailingReader extends Reader {
        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            throw new IOException("boom");
        }

        @Override
        public void close() {
        }
    }

    private static final class FailingWriter extends Writer {
        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            throw new IOException("boom");
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
