package sh.zolt.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.concurrent.RepositoryExecutionLane;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.maven.repository.RepositoryArtifact;
import sh.zolt.maven.repository.RepositoryClientException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalArtifactCacheTest {
    private final CoordinateParser parser = new CoordinateParser();

    @TempDir
    private Path tempDir;

    @Test
    void cachePathsAreDeterministic() {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");

        assertEquals(
                tempDir.resolve("com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom"),
                cache.pomPath(coordinate));
        assertEquals(
                tempDir.resolve("com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.jar"),
                cache.jarPath(coordinate));
    }

    @Test
    void reportsDownloadSettingsFromCoordinator() {
        LocalArtifactCache cache = new LocalArtifactCache(
                tempDir,
                new DownloadCoordinator(3, RepositoryExecutionLane.VIRTUAL));

        assertEquals(3, cache.downloadConcurrency());
        assertEquals(RepositoryExecutionLane.VIRTUAL, cache.repositoryExecutionLane());
        assertTrue(LocalArtifactCache.defaultRoot().endsWith(Path.of(".zolt", "cache")));
    }

    @Test
    void classifierArtifactPathIsDeterministic() {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("io.quarkus:quarkus-custom-deployment:1.0.0");
        ArtifactDescriptor descriptor = ArtifactDescriptor.jar(coordinate, Optional.of("deployment"));

        assertEquals(
                tempDir.resolve("io/quarkus/quarkus-custom-deployment/1.0.0/quarkus-custom-deployment-1.0.0-deployment.jar"),
                cache.artifactPath(descriptor));
    }

    @Test
    void checksumSidecarArtifactPathIsDeterministic() {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        ArtifactDescriptor descriptor = new ArtifactDescriptor(coordinate, Optional.empty(), "pom.sha256");

        assertEquals(
                tempDir.resolve("com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom.sha256"),
                cache.artifactPath(descriptor));
    }

    @Test
    void repeatedPomFetchUsesCachedArtifact() {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        AtomicInteger fetchCount = new AtomicInteger();
        ArtifactFetcher fetcher = requested -> {
            fetchCount.incrementAndGet();
            return artifact(requested, "com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom", "<project/>");
        };

        CachedArtifact first = cache.getOrFetchPom(coordinate, fetcher);
        CachedArtifact second = cache.getOrFetchPom(coordinate, fetcher);

        assertEquals(1, fetchCount.get());
        assertArrayEquals(bytes(first), bytes(second));
        assertEquals(first.cachePath(), second.cachePath());
    }

    @Test
    void repeatedJarFetchUsesCachedArtifact() {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        AtomicInteger fetchCount = new AtomicInteger();
        byte[] jarBytes = new byte[] {0x50, 0x4b, 0x03, 0x04};

        CachedArtifact first = cache.getOrFetchJar(coordinate, requested -> {
            fetchCount.incrementAndGet();
            return new RepositoryArtifact(
                    requested,
                    "com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.jar",
                    URI.create("https://repo.example/guava.jar"),
                    jarBytes);
        });
        CachedArtifact second = cache.getOrFetchJar(coordinate, requested -> {
            throw new AssertionError("cache should avoid the second fetch");
        });

        assertEquals(1, fetchCount.get());
        assertArrayEquals(bytes(first), bytes(second));
    }

    @Test
    void repeatedClassifierArtifactFetchUsesCachedArtifact() {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("io.quarkus:quarkus-custom-deployment:1.0.0");
        ArtifactDescriptor descriptor = ArtifactDescriptor.jar(coordinate, Optional.of("deployment"));
        AtomicInteger fetchCount = new AtomicInteger();
        byte[] jarBytes = new byte[] {0x50, 0x4b, 0x03, 0x04};

        CachedArtifact first = cache.getOrFetchArtifact(descriptor, requested -> {
            fetchCount.incrementAndGet();
            return new RepositoryArtifact(
                    requested,
                    "io/quarkus/quarkus-custom-deployment/1.0.0/quarkus-custom-deployment-1.0.0-deployment.jar",
                    URI.create("https://repo.example/quarkus-custom-deployment-1.0.0-deployment.jar"),
                    jarBytes);
        });
        CachedArtifact second = cache.getOrFetchArtifact(descriptor, requested -> {
            throw new AssertionError("cache should avoid the second fetch");
        });

        assertEquals(1, fetchCount.get());
        assertEquals(
                tempDir.resolve("io/quarkus/quarkus-custom-deployment/1.0.0/quarkus-custom-deployment-1.0.0-deployment.jar"),
                first.cachePath());
        assertArrayEquals(bytes(first), bytes(second));
    }

    @Test
    void repeatedChecksumSidecarFetchUsesCachedArtifact() {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        ArtifactDescriptor descriptor = new ArtifactDescriptor(coordinate, Optional.empty(), "jar.sha1");
        AtomicInteger fetchCount = new AtomicInteger();
        byte[] checksumBytes = "abc123".getBytes(StandardCharsets.UTF_8);

        CachedArtifact first = cache.getOrFetchArtifact(descriptor, requested -> {
            fetchCount.incrementAndGet();
            return new RepositoryArtifact(
                    requested,
                    "com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.jar.sha1",
                    URI.create("https://repo.example/guava-33.4.0-jre.jar.sha1"),
                    checksumBytes);
        });
        CachedArtifact second = cache.getOrFetchArtifact(descriptor, requested -> {
            throw new AssertionError("cache should avoid the second checksum fetch");
        });

        assertEquals(1, fetchCount.get());
        assertEquals(
                "com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.jar.sha1",
                first.repositoryPath());
        assertArrayEquals(checksumBytes, bytes(second));
    }

    @Test
    void cachedOnlyPomDoesNotFetch() {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        cache.getOrFetchPom(coordinate, requested ->
                artifact(requested, "com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom", "<project/>"));

        CachedArtifact artifact = cache.getCachedPom(coordinate);

        assertEquals(cache.pomPath(coordinate), artifact.cachePath());
        assertArrayEquals("<project/>".getBytes(StandardCharsets.UTF_8), bytes(artifact));
    }

    @Test
    void cachedOnlyClassifierArtifactReadsNamespacedPath() throws Exception {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("io.quarkus:quarkus-custom-deployment:1.0.0");
        ArtifactDescriptor descriptor = ArtifactDescriptor.jar(coordinate, Optional.of("deployment"));
        byte[] jarBytes = new byte[] {0x50, 0x4b, 0x03, 0x04};
        Files.createDirectories(cache.artifactPath(descriptor).getParent());
        Files.write(cache.artifactPath(descriptor), jarBytes);

        CachedArtifact artifact = cache.getCachedArtifact(descriptor, "JAR");

        assertEquals(
                "io/quarkus/quarkus-custom-deployment/1.0.0/quarkus-custom-deployment-1.0.0-deployment.jar",
                artifact.repositoryPath());
        assertArrayEquals(jarBytes, bytes(artifact));
    }

    @Test
    void cachedOnlyJarFailsClearlyWhenMissing() {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");

        ArtifactCacheException exception = assertThrows(
                ArtifactCacheException.class,
                () -> cache.getCachedJar(coordinate));

        assertTrue(exception.getMessage().contains("Offline mode requires cached JAR"));
        assertTrue(exception.getMessage().contains("Run the command without --offline"));
    }

    @Test
    void cachedOnlyJarReadsCachedBytes() throws Exception {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        byte[] jarBytes = new byte[] {0x50, 0x4b, 0x03, 0x04};
        Files.createDirectories(cache.jarPath(coordinate).getParent());
        Files.write(cache.jarPath(coordinate), jarBytes);

        CachedArtifact artifact = cache.getCachedJar(coordinate);

        assertEquals(
                "com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.jar",
                artifact.repositoryPath());
        assertArrayEquals(jarBytes, bytes(artifact));
    }

    @Test
    void cachedOnlyPomRejectsEmptyCacheFileWithRemediation() throws Exception {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        Files.createDirectories(cache.pomPath(coordinate).getParent());
        Files.write(cache.pomPath(coordinate), new byte[0]);

        ArtifactCacheException exception = assertThrows(
                ArtifactCacheException.class,
                () -> cache.getCachedPom(coordinate));

        assertTrue(exception.getMessage().contains("Cached artifact at " + cache.pomPath(coordinate)));
        assertTrue(exception.getMessage().contains("Delete it and run the command again."));
    }

    @Test
    void failedDownloadDoesNotCreateCacheFile() {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");

        assertThrows(
                RepositoryClientException.class,
                () -> cache.getOrFetchPom(coordinate, requested -> {
                    throw new RepositoryClientException("network failed");
                }));

        assertFalse(Files.exists(cache.pomPath(coordinate)));
    }

    @Test
    void emptyCachedArtifactIsNotValid() throws Exception {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        Files.createDirectories(cache.pomPath(coordinate).getParent());
        Files.write(cache.pomPath(coordinate), new byte[0]);

        ArtifactCacheException exception = assertThrows(
                ArtifactCacheException.class,
                () -> cache.getOrFetchPom(coordinate, requested -> artifact(requested, "unused", "<project/>")));

        assertTrue(exception.getMessage().contains("is empty"));
        assertTrue(exception.getMessage().contains("Delete it and run the command again."));
    }

    @Test
    void emptyDownloadDoesNotUpdateCache() {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");

        ArtifactCacheException exception = assertThrows(
                ArtifactCacheException.class,
                () -> cache.getOrFetchPom(coordinate, requested -> new RepositoryArtifact(
                        requested,
                        "com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom",
                        URI.create("https://repo.example/guava.pom"),
                        new byte[0])));

        assertEquals("Downloaded artifact com.google.guava:guava:33.4.0-jre is empty. The cache was not updated.", exception.getMessage());
        assertFalse(Files.exists(cache.pomPath(coordinate)));
    }

    @Test
    void relativeCacheRootKeepsReturningRelativeArtifactPaths() {
        LocalArtifactCache cache = new LocalArtifactCache(Path.of("relative-cache"));
        Coordinate coordinate = parser.parse("com.example:demo:1.0.0");

        assertFalse(cache.pomPath(coordinate).isAbsolute());
        assertEquals(
                Path.of("relative-cache/com/example/demo/1.0.0/demo-1.0.0.pom"),
                cache.pomPath(coordinate));
    }

    @Test
    void cacheWriteFailureIsActionableAndDoesNotReportSuccess() throws Exception {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        Files.createDirectories(cache.pomPath(coordinate));

        ArtifactCacheException exception = assertThrows(
                ArtifactCacheException.class,
                () -> cache.getOrFetchPom(coordinate, requested ->
                        artifact(requested, "com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom", "<project/>")));

        assertTrue(exception.getMessage().contains("Could not write cached artifact at " + cache.pomPath(coordinate)));
        assertTrue(exception.getMessage().contains("Check filesystem permissions."));
    }

    private static RepositoryArtifact artifact(Coordinate coordinate, String path, String body) {
        return new RepositoryArtifact(
                coordinate,
                path,
                URI.create("https://repo.example/" + path),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(CachedArtifact artifact) {
        try {
            return Files.readAllBytes(artifact.cachePath());
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
