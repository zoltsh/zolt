package sh.zolt.lockfile.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AtomicLockfileWriterTest {
    @TempDir
    private Path tempDir;

    @Test
    void concurrentWritersCommitOneCompleteFileAndRemoveUniqueTemporaries() throws Exception {
        Path lockfile = tempDir.resolve("zolt.lock");
        String first = "version = 5\n# " + "a".repeat(100_000) + "\n";
        String second = "version = 5\n# " + "b".repeat(100_000) + "\n";
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread firstWriter = writer(start, lockfile, first, failure);
        Thread secondWriter = writer(start, lockfile, second, failure);

        start.countDown();
        firstWriter.join(TimeUnit.SECONDS.toMillis(5));
        secondWriter.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(firstWriter.isAlive());
        assertFalse(secondWriter.isAlive());
        assertNull(failure.get());
        assertTrue(Set.of(first, second).contains(Files.readString(lockfile)));
        try (var files = Files.list(tempDir)) {
            assertEquals(
                    Set.of(
                            lockfile,
                            tempDir.resolve(".zolt")),
                    Set.copyOf(files.toList()));
        }
    }

    @Test
    void independentProcessesSerializeWholeReadModifyWriteTransactions() throws Exception {
        Path lockfile = tempDir.resolve("zolt.lock");
        Files.writeString(lockfile, "version = 5\n");
        MutationProcess first = mutationProcess(
                lockfile,
                "# dependency\n");
        assertEquals("updating", first.readEvent());
        assertEquals("entered", first.readEvent());
        MutationProcess second = mutationProcess(
                lockfile,
                "# toolchain\n");
        try {
            assertEquals("updating", second.readEvent());
            first.release();
            assertEquals("entered", second.readEvent());
        } finally {
            first.release();
            second.release();
        }

        assertTrue(first.process().waitFor(5, TimeUnit.SECONDS));
        assertTrue(second.process().waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, first.process().exitValue());
        assertEquals(0, second.process().exitValue());
        assertEquals(
                "version = 5\n# dependency\n# toolchain\n",
                Files.readString(lockfile));
    }

    @Test
    void updateReturnsTheExactContentCommittedByTheTransaction() throws Exception {
        Path lockfile = tempDir.resolve("zolt.lock");
        Files.writeString(lockfile, "version = 5\n");

        String committed = AtomicLockfileWriter.updateAndReturn(
                lockfile,
                current -> current + "# coverage tooling\n");

        assertEquals("version = 5\n# coverage tooling\n", committed);
        assertEquals(committed, Files.readString(lockfile));
    }

    private static Thread writer(
            CountDownLatch start,
            Path lockfile,
            String content,
            AtomicReference<Throwable> failure) {
        return Thread.ofPlatform().start(() -> {
            try {
                start.await();
                AtomicLockfileWriter.write(lockfile, content);
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
    }

    private static MutationProcess mutationProcess(
            Path lockfile,
            String addition) throws Exception {
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win")
                        ? "java.exe"
                        : "java");
        Process process = new ProcessBuilder(
                java.toString(),
                "-cp",
                String.join(
                        File.pathSeparator,
                        classLocation(AtomicLockfileMutationProcess.class),
                        classLocation(AtomicLockfileWriter.class)),
                AtomicLockfileMutationProcess.class.getName(),
                lockfile.toString(),
                addition)
                .start();
        return new MutationProcess(
                process,
                process.inputReader(),
                process.outputWriter());
    }

    private static String classLocation(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
    }

    private record MutationProcess(
            Process process,
            BufferedReader events,
            BufferedWriter commands) {
        private String readEvent() throws Exception {
            return events.readLine();
        }

        private void release() throws Exception {
            if (process.isAlive()) {
                commands.write("release\n");
                commands.flush();
            }
        }
    }
}
