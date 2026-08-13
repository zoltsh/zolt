package sh.zolt.resolve.metadata.pom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cache.CachedArtifact;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.RawPom;
import sh.zolt.maven.repository.RawPomParser;
import sh.zolt.resolve.metrics.RawPomLoadMetricsSink;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RawPomMetadataLoaderTest {
    private static final Coordinate APP = new Coordinate("com.example", "app", Optional.of("1.0.0"));

    private final RawPomMetadataLoader loader = new RawPomMetadataLoader(new RawPomParser());

    @TempDir
    private Path tempDir;

    @Test
    void parsesAndCachesRawPomByCoordinate() {
        AtomicInteger artifactLoads = new AtomicInteger();
        RecordingMetrics metrics = new RecordingMetrics();

        RawPom first = loader.load(APP, coordinate -> {
            artifactLoads.incrementAndGet();
            return cachedPom(coordinate, "app");
        }, metrics);
        RawPom second = loader.load(APP, coordinate -> {
            artifactLoads.incrementAndGet();
            return cachedPom(coordinate, "app");
        }, metrics);

        assertSame(first, second);
        assertEquals("app", first.artifactId());
        assertEquals(1, artifactLoads.get());
        assertEquals(1, metrics.cacheHits);
        assertEquals(1, metrics.cacheMisses);
        assertTrue(metrics.parseNanos > 0);
    }

    @Test
    void recordsMissAgainWhenParseFails() {
        RecordingMetrics metrics = new RecordingMetrics();

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> loader.load(APP, coordinate -> cachedPom(coordinate, "<not-xml"), metrics));
        RawPom parsed = loader.load(APP, coordinate -> cachedPom(coordinate, "app"), metrics);

        assertEquals("app", parsed.artifactId());
        assertEquals(0, metrics.cacheHits);
        assertEquals(2, metrics.cacheMisses);
        assertTrue(metrics.parseNanos > 0);
    }

    @Test
    void sharesInFlightRawPomLoadBetweenCallers() throws Exception {
        RawPomMetadataLoader loader = new RawPomMetadataLoader(new RawPomParser());
        CountDownLatch cacheHitRecorded = new CountDownLatch(1);
        RecordingMetrics metrics = new RecordingMetrics(cacheHitRecorded);
        AtomicInteger artifactLoads = new AtomicInteger();
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RawPom> first = executor.submit(() -> loader.load(APP, coordinate -> {
                artifactLoads.incrementAndGet();
                loadStarted.countDown();
                await(releaseLoad);
                return cachedPom(coordinate, "app");
            }, metrics));
            assertTrue(loadStarted.await(5, TimeUnit.SECONDS));

            Future<RawPom> second = executor.submit(() -> loader.load(APP, coordinate -> {
                artifactLoads.incrementAndGet();
                return cachedPom(coordinate, "other");
            }, metrics));
            assertTrue(cacheHitRecorded.await(5, TimeUnit.SECONDS));
            releaseLoad.countDown();

            RawPom firstPom = first.get(5, TimeUnit.SECONDS);
            RawPom secondPom = second.get(5, TimeUnit.SECONDS);
            assertSame(firstPom, secondPom);
            assertEquals("app", secondPom.artifactId());
            assertEquals(1, artifactLoads.get());
            assertEquals(1, metrics.cacheHits);
            assertEquals(1, metrics.cacheMisses);
        } finally {
            releaseLoad.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private CachedArtifact cachedPom(Coordinate coordinate, String artifactId) {
        String xml = artifactId.startsWith("<")
                ? artifactId
                : """
                        <project>
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>%s</groupId>
                          <artifactId>%s</artifactId>
                          <version>%s</version>
                        </project>
                        """.formatted(
                        coordinate.groupId(),
                        artifactId,
                        coordinate.version().orElseThrow());
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        Path path = tempDir.resolve("app-1.0.0.pom");
        try {
            Files.write(path, bytes);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
        return new CachedArtifact(
                coordinate,
                "com/example/app/1.0.0/app-1.0.0.pom",
                path,
                bytes.length,
                sha256(bytes),
                "test");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class RecordingMetrics implements RawPomLoadMetricsSink {
        private int cacheHits;
        private int cacheMisses;
        private long parseNanos;
        private final CountDownLatch cacheHitRecorded;

        private RecordingMetrics() {
            this(new CountDownLatch(0));
        }

        private RecordingMetrics(CountDownLatch cacheHitRecorded) {
            this.cacheHitRecorded = cacheHitRecorded;
        }

        @Override
        public synchronized void recordRawPomCacheHit() {
            cacheHits++;
            cacheHitRecorded.countDown();
        }

        @Override
        public synchronized void recordRawPomCacheMiss() {
            cacheMisses++;
        }

        @Override
        public synchronized void recordRawPomParse(long elapsedNanos) {
            parseNanos += elapsedNanos;
        }
    }
}
