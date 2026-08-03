package sh.zolt.build.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.junit.JunitWorkerClient;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.runtime.TestRunException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlainJunitPersistentRequestRunnerTest {
    @TempDir
    private Path tempDir;

    @Test
    void unfilteredPersistentRequestAllowsSupportOnlySources() {
        PlainJunitWorkerPoolRunResult result =
                run(TestSelection.empty());

        assertEquals("[         0 tests found           ]\n", result.output());
        assertEquals(1, result.workerRequests());
    }

    @Test
    void filteredPersistentRequestStillFailsWhenNothingMatches() {
        TestSelection selection = TestSelection.fromCli(
                List.of("com.example.MissingTest"),
                List.of(),
                List.of(),
                List.of());

        TestRunException exception = assertThrows(
                TestRunException.class,
                () -> run(selection));

        assertTrue(exception.getMessage().contains("exit code 2"));
        assertTrue(exception.getMessage().contains("0 tests found"));
    }

    private PlainJunitWorkerPoolRunResult run(TestSelection selection) {
        PlainJunitWorkerSlot slot = new PlainJunitWorkerSlot(
                (javaExecutable,
                        workerClasspath,
                        projectDirectory,
                        testRuntimeClasspath,
                        jvmArguments,
                        environment) -> zeroTestsSession(),
                Path.of("java"),
                List.of(Path.of("zolt-worker.jar")),
                TestJvmArguments.empty(),
                Map.of());
        try {
            return PlainJunitPersistentRequestRunner.run(
                    slot,
                    tempDir,
                    List.of(),
                    tempDir.resolve("target/test-classes"),
                    selection,
                    Optional.empty(),
                    List.of(),
                    Optional.empty());
        } finally {
            slot.close();
        }
    }

    private static PlainJunitWorkerSession zeroTestsSession() {
        return new PlainJunitWorkerSession() {
            @Override
            public PlainJunitWorkerRunResult run(
                    Path testOutputDirectory,
                    TestSelection testSelection,
                    Optional<Path> reportsDirectory,
                    List<String> events,
                    Optional<Path> profileDirectory) {
                return result();
            }

            @Override
            public long startupNanos() {
                return 0L;
            }

            @Override
            public int processStarts() {
                return 1;
            }

            @Override
            public void close() {
            }
        };
    }

    private static PlainJunitWorkerRunResult result() {
        return new PlainJunitWorkerRunResult(
                new JunitWorkerClient.WorkerRunResult(
                        "[         0 tests found           ]\n",
                        2),
                0L,
                1L);
    }
}
