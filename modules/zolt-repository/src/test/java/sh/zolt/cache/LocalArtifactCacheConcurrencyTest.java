package sh.zolt.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.concurrent.RepositoryExecutionLane;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.maven.repository.RepositoryArtifact;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalArtifactCacheConcurrencyTest {
    private final CoordinateParser parser = new CoordinateParser();

    @TempDir
    private Path tempDir;

    @Test
    void concurrentDuplicatePomFetchDownloadsOnce() throws Exception {
        CountDownLatch duplicateJoined = new CountDownLatch(1);
        LocalArtifactCache cache = new LocalArtifactCache(
                tempDir,
                new DownloadCoordinator(4, RepositoryExecutionLane.DEFAULT, duplicateJoined::countDown));
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        AtomicInteger fetchCount = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<CachedArtifact> first = executor.submit(() -> cache.getOrFetchPom(coordinate, requested -> {
                fetchCount.incrementAndGet();
                firstStarted.countDown();
                await(release);
                return artifact(requested, "com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom", "<project/>");
            }));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            Future<CachedArtifact> second = executor.submit(() -> cache.getOrFetchPom(coordinate, requested -> {
                fetchCount.incrementAndGet();
                return artifact(requested, "com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom", "<duplicate/>");
            }));
            assertTrue(duplicateJoined.await(2, TimeUnit.SECONDS));
            release.countDown();

            assertArrayEquals(bytes(first.get()), bytes(second.get()));
            assertEquals(1, fetchCount.get());
            assertEquals("<project/>", Files.readString(cache.pomPath(coordinate)));
        }
    }

    @Test
    void manyConcurrentDuplicateJarFetchesDownloadOnce() throws Exception {
        CountDownLatch duplicateJoined = new CountDownLatch(11);
        LocalArtifactCache cache = new LocalArtifactCache(
                tempDir,
                new DownloadCoordinator(4, RepositoryExecutionLane.DEFAULT, duplicateJoined::countDown));
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        AtomicInteger fetchCount = new AtomicInteger();
        byte[] jarBytes = new byte[] {0x50, 0x4b, 0x03, 0x04};

        try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
            List<Future<CachedArtifact>> futures = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                futures.add(executor.submit(() -> cache.getOrFetchJar(coordinate, requested -> {
                    fetchCount.incrementAndGet();
                    await(duplicateJoined);
                    return new RepositoryArtifact(
                            requested,
                            "com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.jar",
                            URI.create("https://repo.example/guava.jar"),
                            jarBytes);
                })));
            }
            for (Future<CachedArtifact> future : futures) {
                assertArrayEquals(jarBytes, bytes(future.get()));
            }
        }

        assertEquals(1, fetchCount.get());
        assertArrayEquals(jarBytes, Files.readAllBytes(cache.jarPath(coordinate)));
    }

    private static RepositoryArtifact artifact(Coordinate coordinate, String path, String body) {
        return new RepositoryArtifact(
                coordinate,
                path,
                URI.create("https://repo.example/" + path),
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] bytes(CachedArtifact artifact) throws java.io.IOException {
        return Files.readAllBytes(artifact.cachePath());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test latch.", exception);
        }
    }

}
