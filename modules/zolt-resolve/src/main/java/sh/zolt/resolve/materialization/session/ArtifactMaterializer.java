package sh.zolt.resolve.materialization.session;

import sh.zolt.cache.CachedArtifact;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cache.RepositoryCacheScope;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.RepositoryArtifact;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.metrics.ArtifactLoadMetricsSink;
import sh.zolt.resolve.progress.ArtifactProgressListener;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

final class ArtifactMaterializer {
    private final LocalArtifactCache cache;
    private final RepositoryCacheScope cacheScope;
    private final ResolveOptions options;
    private final LocalOverlayMaterializer localOverlayMaterializer;
    private final ArtifactProgressListener progressListener;

    ArtifactMaterializer(
            LocalArtifactCache cache,
            RepositoryCacheScope cacheScope,
            ResolveOptions options,
            LocalOverlayMaterializer localOverlayMaterializer) {
        this.cache = cache;
        this.cacheScope = cacheScope;
        this.options = options;
        this.localOverlayMaterializer = localOverlayMaterializer;
        this.progressListener = options.artifactProgressListener();
    }

    CachedArtifact getPom(
            Coordinate coordinate,
            Function<Coordinate, RepositoryArtifact> fetchPom,
            ArtifactLoadMetricsSink metrics) {
        long started = System.nanoTime();
        Optional<CachedArtifact> overlayArtifact =
                localOverlayMaterializer.materializePom(options.repositoryOverlays(), coordinate);
        if (overlayArtifact.isPresent()) {
            metrics.recordPomCacheHit(elapsedSince(started));
            return overlayArtifact.orElseThrow();
        }
        if (options.offline()) {
            CachedArtifact artifact = cache.getCachedPom(cacheScope, coordinate);
            metrics.recordPomCacheHit(elapsedSince(started));
            return artifact;
        }
        boolean cached = cache.hasPom(cacheScope, coordinate);
        ArtifactDescriptor descriptor = new ArtifactDescriptor(coordinate, Optional.empty(), "pom");
        CachedArtifact artifact = cache.getOrFetchPom(
                cacheScope,
                coordinate,
                ignored -> fetchWithProgress(descriptor, () -> fetchPom.apply(coordinate)));
        if (cached) {
            metrics.recordPomCacheHit(elapsedSince(started));
        } else {
            metrics.recordPomDownload(elapsedSince(started));
        }
        return artifact;
    }

    CachedArtifact getJar(
            Coordinate coordinate,
            Function<Coordinate, RepositoryArtifact> fetchJar,
            ArtifactLoadMetricsSink metrics) {
        long started = System.nanoTime();
        Optional<CachedArtifact> overlayArtifact =
                localOverlayMaterializer.materializeArtifact(options.repositoryOverlays(), ArtifactDescriptor.jar(coordinate));
        if (overlayArtifact.isPresent()) {
            metrics.recordJarCacheHit(elapsedSince(started));
            return overlayArtifact.orElseThrow();
        }
        if (options.offline()) {
            CachedArtifact artifact = cache.getCachedJar(cacheScope, coordinate);
            metrics.recordJarCacheHit(elapsedSince(started));
            return artifact;
        }
        boolean cached = cache.hasJar(cacheScope, coordinate);
        ArtifactDescriptor descriptor = ArtifactDescriptor.jar(coordinate);
        CachedArtifact artifact = cache.getOrFetchJar(
                cacheScope,
                coordinate,
                ignored -> fetchWithProgress(descriptor, () -> fetchJar.apply(coordinate)));
        if (cached) {
            metrics.recordJarCacheHit(elapsedSince(started));
        } else {
            metrics.recordJarDownload(elapsedSince(started));
        }
        return artifact;
    }

    CachedArtifact getArtifact(
            ArtifactDescriptor descriptor,
            Function<ArtifactDescriptor, RepositoryArtifact> fetchArtifact,
            ArtifactLoadMetricsSink metrics) {
        long started = System.nanoTime();
        Optional<CachedArtifact> overlayArtifact =
                localOverlayMaterializer.materializeArtifact(options.repositoryOverlays(), descriptor);
        if (overlayArtifact.isPresent()) {
            metrics.recordArtifactCacheHit(elapsedSince(started));
            return overlayArtifact.orElseThrow();
        }
        if (options.offline()) {
            CachedArtifact artifact =
                    cache.getCachedArtifact(cacheScope, descriptor, descriptor.extension().toUpperCase(Locale.ROOT));
            metrics.recordArtifactCacheHit(elapsedSince(started));
            return artifact;
        }
        boolean cached = cache.hasArtifact(cacheScope, descriptor);
        CachedArtifact artifact = cache.getOrFetchArtifact(
                cacheScope,
                descriptor,
                ignored -> fetchWithProgress(descriptor, () -> fetchArtifact.apply(descriptor)));
        if (cached) {
            metrics.recordArtifactCacheHit(elapsedSince(started));
        } else {
            metrics.recordArtifactDownload(elapsedSince(started));
        }
        return artifact;
    }

    private RepositoryArtifact fetchWithProgress(
            ArtifactDescriptor descriptor,
            Supplier<RepositoryArtifact> fetcher) {
        progressListener.onStart(descriptor);
        try {
            RepositoryArtifact artifact = fetcher.get();
            progressListener.onComplete(descriptor, artifact.bytes().length);
            return artifact;
        } catch (RuntimeException exception) {
            progressListener.onFailure(descriptor, exception);
            throw exception;
        } catch (Error error) {
            progressListener.onFailure(descriptor, error);
            throw error;
        }
    }

    private static long elapsedSince(long started) {
        return Math.max(0L, System.nanoTime() - started);
    }
}
