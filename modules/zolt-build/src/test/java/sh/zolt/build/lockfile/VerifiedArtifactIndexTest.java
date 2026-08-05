package sh.zolt.build.lockfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.lockfile.VerifiedArtifactIndex.VerificationResult;
import sh.zolt.lockfile.toml.LockfileReadException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class VerifiedArtifactIndexTest {
    @TempDir
    private Path tempDir;

    private final VerifiedArtifactIndex index = new VerifiedArtifactIndex();

    @Test
    void hashesEachDistinctPathOnceAcrossRepeatedRequests() throws IOException {
        Path first = write("first.jar", "first bytes");
        Path second = write("second.jar", "second bytes");
        AtomicInteger hashCalls = new AtomicInteger();

        for (int repeat = 0; repeat < 5; repeat++) {
            index.verifyOnce(first, sha256(first), counting(hashCalls));
            index.verifyOnce(second, sha256(second), counting(hashCalls));
        }

        assertEquals(2, hashCalls.get());
        assertEquals(2, index.metrics().hashes());
        assertEquals(2, index.metrics().paths());
        assertEquals(8, index.metrics().cacheHits());
    }

    @Test
    void hashesOncePerPathWhenProjectionsRunConcurrently() throws Exception {
        List<Path> artifacts = new ArrayList<>();
        for (int number = 0; number < 8; number++) {
            artifacts.add(write("artifact-" + number + ".jar", "bytes " + number));
        }
        Map<Path, AtomicInteger> hashCallsByPath = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int projection = 0; projection < 16; projection++) {
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (Path artifact : artifacts) {
                        index.verifyOnce(
                                artifact,
                                sha256(artifact),
                                path -> {
                                    hashCallsByPath
                                            .computeIfAbsent(path, key -> new AtomicInteger())
                                            .incrementAndGet();
                                    return digest(path);
                                });
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertEquals(artifacts.size(), hashCallsByPath.size());
        hashCallsByPath.forEach((path, calls) -> assertEquals(1, calls.get(), "hashed more than once: " + path));
        assertEquals(artifacts.size(), index.metrics().hashes());
        assertEquals(16 * artifacts.size() - artifacts.size(), index.metrics().cacheHits());
    }

    @Test
    void hashesDistinctPathsWithoutSerialisingThem() throws IOException {
        Path first = write("first.jar", "first bytes");
        Path second = write("second.jar", "second bytes");
        CountDownLatch bothStarted = new CountDownLatch(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            // Each hash blocks until the other has started: a design that held a lock covering all
            // paths while hashing would deadlock here rather than finish.
            List<Future<?>> futures = List.of(
                    executor.submit(() -> index.verifyOnce(first, sha256(first), blocking(bothStarted))),
                    executor.submit(() -> index.verifyOnce(second, sha256(second), blocking(bothStarted))));
            for (Future<?> future : futures) {
                assertNotNull(future.get(10, TimeUnit.SECONDS));
            }
        } catch (Exception exception) {
            throw new AssertionError("Hashing distinct paths did not proceed concurrently.", exception);
        }

        assertEquals(2, index.metrics().hashes());
    }

    @Test
    void rejectsConflictingExpectationsForTheSamePath() throws IOException {
        Path jar = write("demo.jar", "jar bytes");
        index.verifyOnce(jar, sha256(jar));

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> index.verifyOnce(jar, sha256("different bytes")));

        assertTrue(exception.getMessage().contains("Conflicting integrity expectations"));
        assertTrue(exception.getMessage().contains(jar.toAbsolutePath().normalize().toString()));
        assertTrue(exception.getMessage().contains(sha256(jar)));
        assertTrue(exception.getMessage().contains(sha256("different bytes")));
        assertTrue(exception.getMessage().contains("zolt resolve"));
    }

    @Test
    void reportsMismatchWithoutHashingAgain() throws IOException {
        Path jar = write("demo.jar", "actual bytes");
        AtomicInteger hashCalls = new AtomicInteger();

        VerificationResult first = index.verifyOnce(jar, sha256("expected bytes"), counting(hashCalls));
        VerificationResult second = index.verifyOnce(jar, sha256("expected bytes"), counting(hashCalls));

        assertFalse(first.verified());
        assertFalse(second.verified());
        assertEquals(sha256(jar), first.actualSha256());
        assertEquals(sha256(jar), second.actualSha256());
        assertFalse(first.cacheHit());
        assertTrue(second.cacheHit());
        assertEquals(1, hashCalls.get());
    }

    @Test
    void memoisesReadFailuresSoAMissingArtifactIsProbedOnce() {
        Path missing = tempDir.resolve("missing.jar");

        VerificationResult first = index.verifyOnce(missing, sha256("expected bytes"));
        VerificationResult second = index.verifyOnce(missing, sha256("expected bytes"));

        assertNotNull(first.failure());
        assertNotNull(second.failure());
        assertFalse(first.verified());
        assertFalse(second.verified());
        assertTrue(first.failure().getMessage().contains("missing"));
        assertEquals(1, index.metrics().hashes());
        assertEquals(1, index.metrics().cacheHits());
    }

    @Test
    void hashesNothingUntilAPathIsActuallyRequested() throws IOException {
        write("untouched.jar", "never asked for");

        assertEquals(VerifiedArtifactIndex.Metrics.empty(), index.metrics());
        assertFalse(index.requested(tempDir.resolve("untouched.jar")));
    }

    @Test
    void recordsBytesAndDurationForTheArtifactsItReads() throws IOException {
        Path jar = write("demo.jar", "jar bytes");

        VerificationResult result = index.verifyOnce(jar, sha256(jar));

        assertTrue(result.verified());
        assertEquals(Files.size(jar), index.metrics().bytes());
        assertTrue(index.metrics().nanos() > 0L);
        assertTrue(index.requested(jar));
    }

    private VerifiedArtifactIndex.ArtifactContentHasher counting(AtomicInteger hashCalls) {
        return path -> {
            hashCalls.incrementAndGet();
            return digest(path);
        };
    }

    private static VerifiedArtifactIndex.ArtifactContentHasher blocking(CountDownLatch bothStarted) {
        return path -> {
            bothStarted.countDown();
            try {
                if (!bothStarted.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Distinct paths were hashed one after the other.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return digest(path);
        };
    }

    private Path write(String name, String content) throws IOException {
        Path path = tempDir.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static String sha256(Path path) throws IOException {
        return sha256(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String digest(Path path) {
        try {
            return sha256(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
