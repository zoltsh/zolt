package sh.zolt.lockfile.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            assertEquals(List.of(lockfile), files.toList());
        }
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
}
