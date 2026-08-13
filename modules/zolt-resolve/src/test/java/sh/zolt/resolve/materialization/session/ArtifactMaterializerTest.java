package sh.zolt.resolve.materialization.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.cache.CachedArtifact;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cache.RepositoryCacheScope;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.RepositoryArtifact;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.metrics.ArtifactLoadMetricsSink;
import sh.zolt.resolve.progress.ArtifactProgressListener;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ArtifactMaterializerTest {
    private static final Coordinate APP = new Coordinate("com.example", "app", Optional.of("1.0.0"));

    @TempDir
    private Path tempDir;

    @Test
    void downloadsMissingJarThenReusesCachedJar() {
        RecordingArtifactProgressListener listener = new RecordingArtifactProgressListener();
        ArtifactMaterializer materializer =
                materializer(ResolveOptions.defaults().withArtifactProgressListener(listener));
        RecordingMetrics metrics = new RecordingMetrics();
        AtomicInteger fetches = new AtomicInteger();
        ArtifactDescriptor descriptor = ArtifactDescriptor.jar(APP);

        CachedArtifact first = materializer.getJar(APP, coordinate -> {
            fetches.incrementAndGet();
            return artifact(coordinate, "com/example/app/1.0.0/app-1.0.0.jar", new byte[] {1, 2, 3});
        }, metrics);
        CachedArtifact second = materializer.getJar(APP, coordinate -> {
            fetches.incrementAndGet();
            return artifact(coordinate, "com/example/app/1.0.0/app-1.0.0.jar", new byte[] {4});
        }, metrics);

        assertArrayEquals(new byte[] {1, 2, 3}, first.bytes());
        assertArrayEquals(first.bytes(), second.bytes());
        assertEquals(1, fetches.get());
        assertEquals(1, metrics.jarDownloads);
        assertEquals(1, metrics.jarCacheHits);
        assertEquals(
                List.of(
                        ProgressEvent.start(descriptor),
                        ProgressEvent.complete(descriptor, 3)),
                listener.events());
    }

    @Test
    void downloadsMissingPomThenReusesCachedPom() {
        RecordingArtifactProgressListener listener = new RecordingArtifactProgressListener();
        ArtifactMaterializer materializer =
                materializer(ResolveOptions.defaults().withArtifactProgressListener(listener));
        RecordingMetrics metrics = new RecordingMetrics();
        AtomicInteger fetches = new AtomicInteger();
        ArtifactDescriptor descriptor = new ArtifactDescriptor(APP, Optional.empty(), "pom");

        CachedArtifact first = materializer.getPom(APP, coordinate -> {
            fetches.incrementAndGet();
            return artifact(coordinate, "com/example/app/1.0.0/app-1.0.0.pom", new byte[] {1, 2});
        }, metrics);
        CachedArtifact second = materializer.getPom(APP, coordinate -> {
            fetches.incrementAndGet();
            return artifact(coordinate, "com/example/app/1.0.0/app-1.0.0.pom", new byte[] {3});
        }, metrics);

        assertArrayEquals(new byte[] {1, 2}, first.bytes());
        assertArrayEquals(first.bytes(), second.bytes());
        assertEquals(1, fetches.get());
        assertEquals(1, metrics.pomDownloads);
        assertEquals(1, metrics.pomCacheHits);
        assertEquals(
                List.of(
                        ProgressEvent.start(descriptor),
                        ProgressEvent.complete(descriptor, 2)),
                listener.events());
    }

    @Test
    void usesCachedTypedArtifactInOfflineMode() {
        ArtifactDescriptor descriptor = new ArtifactDescriptor(APP, Optional.empty(), "properties");
        RecordingArtifactProgressListener onlineListener = new RecordingArtifactProgressListener();
        ArtifactMaterializer online =
                materializer(ResolveOptions.defaults().withArtifactProgressListener(onlineListener));
        RecordingMetrics metrics = new RecordingMetrics();
        online.getArtifact(
                descriptor,
                ignored -> artifact(APP, "com/example/app/1.0.0/app-1.0.0.properties", new byte[] {9}),
                metrics);

        RecordingArtifactProgressListener offlineListener = new RecordingArtifactProgressListener();
        ArtifactMaterializer offline =
                materializer(ResolveOptions.offline(true).withArtifactProgressListener(offlineListener));
        CachedArtifact artifact = offline.getArtifact(
                descriptor,
                ignored -> {
                    throw new AssertionError("offline materializer should not fetch");
                },
                metrics);

        assertArrayEquals(new byte[] {9}, artifact.bytes());
        assertEquals(1, metrics.artifactDownloads);
        assertEquals(1, metrics.artifactCacheHits);
        assertEquals(
                List.of(
                        ProgressEvent.start(descriptor),
                        ProgressEvent.complete(descriptor, 1)),
                onlineListener.events());
        assertEquals(List.of(), offlineListener.events());
    }

    @Test
    void failedArtifactDownloadEmitsFailureEventAndPreservesFailure() {
        RecordingArtifactProgressListener listener = new RecordingArtifactProgressListener();
        ArtifactMaterializer materializer =
                materializer(ResolveOptions.defaults().withArtifactProgressListener(listener));
        RecordingMetrics metrics = new RecordingMetrics();
        RuntimeException failure = new RuntimeException("repository unavailable");
        ArtifactDescriptor descriptor = ArtifactDescriptor.jar(APP);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> materializer.getJar(APP, ignored -> {
                    throw failure;
                }, metrics));

        assertSame(failure, thrown);
        assertEquals(0, metrics.jarDownloads);
        assertEquals(0, metrics.jarCacheHits);
        assertEquals(
                List.of(
                        ProgressEvent.start(descriptor),
                        ProgressEvent.failure(descriptor, "repository unavailable")),
                listener.events());
    }

    @Test
    void failedArtifactDownloadEmitsFailureEventForErrors() {
        RecordingArtifactProgressListener listener = new RecordingArtifactProgressListener();
        ArtifactMaterializer materializer =
                materializer(ResolveOptions.defaults().withArtifactProgressListener(listener));
        RecordingMetrics metrics = new RecordingMetrics();
        AssertionError failure = new AssertionError("repository crashed");
        ArtifactDescriptor descriptor = ArtifactDescriptor.jar(APP);

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> materializer.getJar(APP, ignored -> {
                    throw failure;
                }, metrics));

        assertSame(failure, thrown);
        assertEquals(0, metrics.jarDownloads);
        assertEquals(0, metrics.jarCacheHits);
        assertEquals(
                List.of(
                        ProgressEvent.start(descriptor),
                        ProgressEvent.failure(descriptor, "repository crashed")),
                listener.events());
    }

    private ArtifactMaterializer materializer(ResolveOptions options) {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir.resolve("cache"));
        return new ArtifactMaterializer(
                cache,
                RepositoryCacheScope.of("test-repository"),
                options,
                new LocalOverlayMaterializer(cache));
    }

    private static RepositoryArtifact artifact(Coordinate coordinate, String path, byte[] bytes) {
        return new RepositoryArtifact(coordinate, path, URI.create("https://repo.example/" + path), bytes);
    }

    private record ProgressEvent(String kind, ArtifactDescriptor descriptor, long bytes, String failureMessage) {
        private static ProgressEvent start(ArtifactDescriptor descriptor) {
            return new ProgressEvent("start", descriptor, -1L, "");
        }

        private static ProgressEvent complete(ArtifactDescriptor descriptor, long bytes) {
            return new ProgressEvent("complete", descriptor, bytes, "");
        }

        private static ProgressEvent failure(ArtifactDescriptor descriptor, String failureMessage) {
            return new ProgressEvent("failure", descriptor, -1L, failureMessage);
        }
    }

    private static final class RecordingArtifactProgressListener implements ArtifactProgressListener {
        private final CopyOnWriteArrayList<ProgressEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onStart(ArtifactDescriptor descriptor) {
            events.add(ProgressEvent.start(descriptor));
        }

        @Override
        public void onComplete(ArtifactDescriptor descriptor, long bytes) {
            events.add(ProgressEvent.complete(descriptor, bytes));
        }

        @Override
        public void onFailure(ArtifactDescriptor descriptor, Throwable failure) {
            events.add(ProgressEvent.failure(descriptor, failure.getMessage()));
        }

        private List<ProgressEvent> events() {
            return List.copyOf(events);
        }
    }

    private static final class RecordingMetrics implements ArtifactLoadMetricsSink {
        private int pomCacheHits;
        private int pomDownloads;
        private int jarCacheHits;
        private int jarDownloads;
        private int artifactCacheHits;
        private int artifactDownloads;

        @Override
        public void recordPomCacheHit(long elapsedNanos) {
            pomCacheHits++;
        }

        @Override
        public void recordPomDownload(long elapsedNanos) {
            pomDownloads++;
        }

        @Override
        public void recordJarCacheHit(long elapsedNanos) {
            jarCacheHits++;
        }

        @Override
        public void recordJarDownload(long elapsedNanos) {
            jarDownloads++;
        }

        @Override
        public void recordArtifactCacheHit(long elapsedNanos) {
            artifactCacheHits++;
        }

        @Override
        public void recordArtifactDownload(long elapsedNanos) {
            artifactDownloads++;
        }
    }
}
