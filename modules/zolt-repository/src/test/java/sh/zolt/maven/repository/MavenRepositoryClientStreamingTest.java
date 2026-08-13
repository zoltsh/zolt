package sh.zolt.maven.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cache.CachedArtifact;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cache.RepositoryCacheScope;
import sh.zolt.maven.Coordinate;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenRepositoryClientStreamingTest extends MavenRepositoryClientTestSupport {
    private static final Coordinate APP =
            new Coordinate("com.example", "app", Optional.of("1.0.0"));

    @TempDir
    private Path tempDir;

    @Test
    void streamsChunkedResponseWithoutContentLength() throws Exception {
        byte[] expected = new byte[] {1, 2, 3, 4, 5};
        server.createContext("/chunked/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(expected, 0, 2);
                exchange.getResponseBody().flush();
                exchange.getResponseBody().write(expected, 2, 3);
            }
        });
        Path downloads = tempDir.resolve("chunked-downloads");

        try (RepositoryArtifact artifact = clientWithLimits(8, 8).fetchJar(
                repository("chunked"),
                APP,
                RepositoryAuthentication.none(),
                RepositoryDownloadListener.NOOP,
                downloads)) {
            assertEquals(expected.length, artifact.size());
            assertArrayEquals(expected, Files.readAllBytes(artifact.temporaryPath()));
        }

        assertDownloadDirectoryEmpty(downloads);
    }

    @Test
    void appliesSeparatePomAndArtifactCeilingsFromContentLength() throws Exception {
        byte[] body = new byte[] {1, 2, 3, 4, 5};
        server.createContext("/limits/", exchange -> respond(exchange, 200, body));
        MavenRepositoryClient bounded = clientWithLimits(4, 8);
        Path downloads = tempDir.resolve("limit-downloads");

        RepositoryClientException oversized = assertThrows(
                RepositoryClientException.class,
                () -> bounded.fetchPom(
                        repository("limits"),
                        APP,
                        RepositoryAuthentication.none(),
                        RepositoryDownloadListener.NOOP,
                        downloads));
        assertTrue(oversized.getMessage().contains("declared 5 bytes exceeds the 4 byte limit"));

        try (RepositoryArtifact artifact = bounded.fetchJar(
                repository("limits"),
                APP,
                RepositoryAuthentication.none(),
                RepositoryDownloadListener.NOOP,
                downloads)) {
            assertEquals(5L, artifact.size());
        }
        assertDownloadDirectoryEmpty(downloads);
    }

    @Test
    void rejectsChunkedOversizeAndDeletesPartialDownload() throws Exception {
        server.createContext("/observed-oversize/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(new byte[] {1, 2, 3});
                exchange.getResponseBody().flush();
                exchange.getResponseBody().write(new byte[] {4, 5});
            }
        });
        Path downloads = tempDir.resolve("oversize-downloads");

        RepositoryClientException exception = assertThrows(
                RepositoryClientException.class,
                () -> clientWithLimits(8, 4).fetchJar(
                        repository("observed-oversize"),
                        APP,
                        RepositoryAuthentication.none(),
                        RepositoryDownloadListener.NOOP,
                        downloads));

        assertTrue(exception.getMessage().contains("received more than 4 bytes"));
        assertDownloadDirectoryEmpty(downloads);
    }

    @Test
    void deletesPartialDownloadWhenTransportEndsMidstream() throws Exception {
        server.createContext("/midstream/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 10);
                exchange.getResponseBody().write(new byte[] {1, 2, 3});
                exchange.getResponseBody().flush();
            }
        });
        Path downloads = tempDir.resolve("midstream-downloads");

        assertThrows(
                RepositoryClientException.class,
                () -> clientWithLimits(16, 16).fetchJar(
                        repository("midstream"),
                        APP,
                        RepositoryAuthentication.none(),
                        RepositoryDownloadListener.NOOP,
                        downloads));

        assertDownloadDirectoryEmpty(downloads);
    }

    @Test
    void interruptionCancelsAndDeletesPartialDownload() throws Exception {
        CountDownLatch firstChunkSent = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        server.createContext("/interrupted/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(new byte[] {1, 2, 3});
                exchange.getResponseBody().flush();
                firstChunkSent.countDown();
                await(releaseServer);
                exchange.getResponseBody().write(new byte[] {4, 5, 6});
            } catch (IOException ignored) {
                // Expected when the interrupted client cancels the exchange.
            }
        });
        Path downloads = tempDir.resolve("interrupted-downloads");
        Files.createDirectories(downloads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread download = new Thread(() -> {
            try (RepositoryArtifact ignored = clientWithLimits(16, 16).fetchJar(
                    repository("interrupted"),
                    APP,
                    RepositoryAuthentication.none(),
                    RepositoryDownloadListener.NOOP,
                    downloads)) {
                failure.set(new AssertionError("interrupted download unexpectedly completed"));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "interrupted-repository-download");

        try (WatchService downloadsChanged = downloads.getFileSystem().newWatchService()) {
            downloads.register(
                    downloadsChanged,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE);
            try {
                download.start();
                assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS));
                assertTrue(awaitDownloadFile(downloads, downloadsChanged));
                download.interrupt();
                download.join(Duration.ofSeconds(5));
                assertFalse(download.isAlive());
                assertNotNull(failure.get());
                assertTrue(failure.get().getMessage().contains("Download interrupted"));
            } finally {
                releaseServer.countDown();
                download.interrupt();
                download.join(Duration.ofSeconds(5));
            }
            assertTrue(awaitDownloadDirectoryEmpty(downloads, downloadsChanged));
        }
    }

    @Test
    void eightConcurrentLargeDownloadsAndLargeCacheHitsRemainFileBacked() throws Exception {
        int bodySize = 2 * 1024 * 1024;
        byte[] body = new byte[bodySize];
        Arrays.fill(body, (byte) 0x5a);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/large/", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, body);
        });
        MavenRepositoryClient streaming = clientWithLimits(8, bodySize + 1L);
        Path cacheRoot = tempDir.resolve("large-cache");
        LocalArtifactCache cache = new LocalArtifactCache(cacheRoot);
        RepositoryCacheScope scope = RepositoryCacheScope.of("large-repository");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<CachedArtifact>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                Coordinate coordinate = new Coordinate(
                        "com.example",
                        "large-" + index,
                        Optional.of("1.0.0"));
                futures.add(executor.submit(() -> cache.getOrFetchJar(
                        scope,
                        coordinate,
                        (requested, downloadDirectory) -> streaming.fetchJar(
                                repository("large"),
                                requested,
                                RepositoryAuthentication.none(),
                                RepositoryDownloadListener.NOOP,
                                downloadDirectory))));
            }

            List<CachedArtifact> artifacts = new ArrayList<>();
            for (Future<CachedArtifact> future : futures) {
                CachedArtifact artifact = future.get(20, TimeUnit.SECONDS);
                artifacts.add(artifact);
                assertEquals(bodySize, artifact.size());
                assertEquals(bodySize, Files.size(artifact.cachePath()));
            }
            assertEquals(8, requests.get());

            Coordinate first = artifacts.getFirst().coordinate();
            CachedArtifact hit = cache.getOrFetchJar(scope, first, (requested, downloadDirectory) -> {
                throw new AssertionError("large cache hit attempted a repository download");
            });
            assertEquals(artifacts.getFirst().sha256(), hit.sha256());
            assertEquals(8, requests.get());
        } finally {
            executor.shutdownNow();
        }

        assertTrue(Arrays.stream(CachedArtifact.class.getRecordComponents())
                .noneMatch(component -> component.getType() == byte[].class));
        assertTrue(Arrays.stream(RepositoryArtifact.class.getRecordComponents())
                .noneMatch(component -> component.getType() == byte[].class));
        assertDownloadDirectoryEmpty(cacheRoot.resolve(".downloads"));
    }

    private MavenRepositoryClient clientWithLimits(long pomBytes, long artifactBytes) {
        return new MavenRepositoryClient(
                HttpClient.newHttpClient(),
                new MavenRepositoryPathBuilder(),
                new RepositoryHttpPolicy(Duration.ofSeconds(5), 1, Duration.ZERO),
                new RepositoryDownloadLimits(pomBytes, artifactBytes, pomBytes));
    }

    private URI repository(String name) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + name + "/");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean awaitDownloadFile(Path directory, WatchService changes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            if (Files.isDirectory(directory)) {
                try (var files = Files.list(directory)) {
                    if (files.findAny().isPresent()) {
                        return true;
                    }
                }
            }
            if (!awaitChange(changes, deadline)) {
                return false;
            }
        }
    }

    private static boolean awaitDownloadDirectoryEmpty(Path directory, WatchService changes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            if (downloadDirectoryEmpty(directory)) {
                return true;
            }
            if (!awaitChange(changes, deadline)) {
                return false;
            }
        }
    }

    private static boolean awaitChange(WatchService changes, long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
            return false;
        }
        WatchKey key = changes.poll(remaining, TimeUnit.NANOSECONDS);
        if (key == null) {
            return false;
        }
        key.pollEvents();
        return key.reset();
    }

    private static void assertDownloadDirectoryEmpty(Path directory) throws Exception {
        assertTrue(downloadDirectoryEmpty(directory), "download directory should contain no partial files");
    }

    private static boolean downloadDirectoryEmpty(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return true;
        }
        try (var files = Files.list(directory)) {
            return files.findAny().isEmpty();
        }
    }
}
