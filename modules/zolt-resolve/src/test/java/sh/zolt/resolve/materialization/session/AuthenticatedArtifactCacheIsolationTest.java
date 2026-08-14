package sh.zolt.resolve.materialization.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cache.CachedArtifact;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cache.RepositoryCacheScope;
import sh.zolt.cache.RepositoryCacheScopeResolver;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.RepositoryAccess;
import sh.zolt.maven.repository.RepositoryArtifact;
import sh.zolt.maven.repository.RepositoryAuthentication;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.metrics.ArtifactLoadMetricsSink;

final class AuthenticatedArtifactCacheIsolationTest {
    private static final Coordinate LIB = new Coordinate("com.acme", "private", Optional.of("1.0.0"));
    private static final String CONFIG_IDENTITY =
            "repository\tprivate\thttps://repo.example/maven2\tcreds\ncredential\tcreds\ttoken\tTOKEN";

    @Test
    void separateInvocationsWithDifferentTokenValuesReceiveTheirOwnPomAndJarBytes(@TempDir Path cacheRoot)
            throws IOException {
        AtomicInteger pomFetches = new AtomicInteger();
        AtomicInteger jarFetches = new AtomicInteger();
        ArtifactMaterializer principalA = materializer(cacheRoot, "principal-a-token");
        CachedArtifact pomA = principalA.getPom(LIB, (coordinate, ignored) -> {
            pomFetches.incrementAndGet();
            return artifact(coordinate, ".pom", new byte[] {1, 1});
        }, new NoopMetrics());
        CachedArtifact jarA = principalA.getJar(LIB, (coordinate, ignored) -> {
            jarFetches.incrementAndGet();
            return artifact(coordinate, ".jar", new byte[] {1, 2, 3});
        }, new NoopMetrics());

        ArtifactMaterializer principalB = materializer(cacheRoot, "principal-b-token");
        CachedArtifact pomB = principalB.getPom(LIB, (coordinate, ignored) -> {
            pomFetches.incrementAndGet();
            return artifact(coordinate, ".pom", new byte[] {2, 2});
        }, new NoopMetrics());
        CachedArtifact jarB = principalB.getJar(LIB, (coordinate, ignored) -> {
            jarFetches.incrementAndGet();
            return artifact(coordinate, ".jar", new byte[] {4, 5, 6});
        }, new NoopMetrics());

        ArtifactMaterializer principalAAgain = materializer(cacheRoot, "principal-a-token");
        CachedArtifact reusedPom = principalAAgain.getPom(LIB, (coordinate, ignored) -> {
            throw new AssertionError("the same credential context should reuse its POM");
        }, new NoopMetrics());
        CachedArtifact reusedJar = principalAAgain.getJar(LIB, (coordinate, ignored) -> {
            throw new AssertionError("the same credential context should reuse its JAR");
        }, new NoopMetrics());

        assertArrayEquals(new byte[] {1, 1}, Files.readAllBytes(pomA.cachePath()));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(jarA.cachePath()));
        assertArrayEquals(new byte[] {2, 2}, Files.readAllBytes(pomB.cachePath()));
        assertArrayEquals(new byte[] {4, 5, 6}, Files.readAllBytes(jarB.cachePath()));
        assertArrayEquals(Files.readAllBytes(pomA.cachePath()), Files.readAllBytes(reusedPom.cachePath()));
        assertArrayEquals(Files.readAllBytes(jarA.cachePath()), Files.readAllBytes(reusedJar.cachePath()));
        assertEquals(2, pomFetches.get());
        assertEquals(2, jarFetches.get());
    }

    private static ArtifactMaterializer materializer(Path cacheRoot, String token) {
        LocalArtifactCache cache = new LocalArtifactCache(cacheRoot);
        RepositoryCacheScope scope = new RepositoryCacheScopeResolver(cacheRoot)
                .resolve(CONFIG_IDENTITY, access(token));
        return new ArtifactMaterializer(
                cache,
                scope,
                ResolveOptions.defaults(),
                new LocalOverlayMaterializer(cache));
    }

    private static List<RepositoryAccess> access(String token) {
        return List.of(new RepositoryAccess(
                "private",
                URI.create("https://repo.example/maven2"),
                Optional.of(RepositoryAuthentication.bearer(token))));
    }

    private static RepositoryArtifact artifact(Coordinate coordinate, String extension, byte[] bytes) {
        String base = "com/acme/private/1.0.0/private-1.0.0" + extension;
        return new RepositoryArtifact(
                coordinate,
                base,
                URI.create("https://repo.example/" + base),
                "private",
                bytes);
    }

    private static final class NoopMetrics implements ArtifactLoadMetricsSink {
        @Override
        public void recordPomCacheHit(long ignored) {}

        @Override
        public void recordPomDownload(long ignored) {}

        @Override
        public void recordJarCacheHit(long ignored) {}

        @Override
        public void recordJarDownload(long ignored) {}

        @Override
        public void recordArtifactCacheHit(long ignored) {}

        @Override
        public void recordArtifactDownload(long ignored) {}
    }
}
