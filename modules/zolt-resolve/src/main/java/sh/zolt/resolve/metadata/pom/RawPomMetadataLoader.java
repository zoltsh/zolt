package sh.zolt.resolve.metadata.pom;

import sh.zolt.cache.CachedArtifact;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.RawPom;
import sh.zolt.maven.repository.RawPomParser;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.metrics.RawPomLoadMetricsSink;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

public final class RawPomMetadataLoader {
    private final RawPomParser rawPomParser;
    private final Map<String, RawPom> rawPoms = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<RawPom>> rawPomLoads = new ConcurrentHashMap<>();

    public RawPomMetadataLoader(RawPomParser rawPomParser) {
        this.rawPomParser = rawPomParser;
    }

    public RawPom load(
            Coordinate coordinate,
            Function<Coordinate, CachedArtifact> pomArtifactLoader,
            RawPomLoadMetricsSink metrics) {
        String key = coordinate.toString();
        RawPom cached = rawPoms.get(key);
        if (cached != null) {
            metrics.recordRawPomCacheHit();
            return cached;
        }
        CompletableFuture<RawPom> pending = new CompletableFuture<>();
        CompletableFuture<RawPom> existing = rawPomLoads.putIfAbsent(key, pending);
        if (existing != null) {
            metrics.recordRawPomCacheHit();
            return awaitRawPom(key, existing);
        }
        metrics.recordRawPomCacheMiss();
        try {
            CachedArtifact pomArtifact = pomArtifactLoader.apply(coordinate);
            long started = System.nanoTime();
            RawPom parsed = rawPomParser.parse(readPom(pomArtifact));
            metrics.recordRawPomParse(elapsedSince(started));
            rawPoms.put(key, parsed);
            pending.complete(parsed);
            return parsed;
        } catch (RuntimeException exception) {
            pending.completeExceptionally(exception);
            throw exception;
        } catch (Error error) {
            pending.completeExceptionally(error);
            throw error;
        } finally {
            rawPomLoads.remove(key, pending);
        }
    }

    private static RawPom awaitRawPom(String key, CompletableFuture<RawPom> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResolveException(
                    "Interrupted while waiting for in-flight raw POM metadata "
                            + key
                            + ". Try again.",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new ResolveException(
                    "Could not load in-flight raw POM metadata "
                            + key
                            + ". Try again.",
                    cause);
        }
    }

    private static byte[] readPom(CachedArtifact artifact) {
        try {
            return Files.readAllBytes(artifact.cachePath());
        } catch (IOException exception) {
            throw new ResolveException(
                    "Could not read cached POM at " + artifact.cachePath() + ".",
                    exception);
        }
    }

    private static long elapsedSince(long started) {
        return Math.max(0L, System.nanoTime() - started);
    }
}
