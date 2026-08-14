package sh.zolt.resolve.materialization.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cache.CachedArtifact;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cache.RepositoryCacheScope;
import sh.zolt.cache.ScopedPomCacheReader;
import sh.zolt.lockfile.CacheRelativePath;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.MavenRepositoryPathBuilder;
import sh.zolt.resolve.materialization.RepositoryOverlay;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalOverlayMaterializerTest {
    private final MavenRepositoryPathBuilder pathBuilder = new MavenRepositoryPathBuilder();

    @TempDir
    private Path tempDir;

    @Test
    void materializesPomFromMavenLocalOverlayAndRecordsSource() throws IOException {
        Coordinate coordinate = coordinate("com.example", "app", "1.0.0");
        byte[] bytes = "<project/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path mavenLocalRoot = writeMavenLocal(pathBuilder.pomPath(coordinate), bytes);
        Path cacheRoot = tempDir.resolve("cache");
        Optional<CachedArtifact> artifact = new LocalOverlayMaterializer(new LocalArtifactCache(cacheRoot))
                .materializePom(List.of(RepositoryOverlay.mavenLocal(mavenLocalRoot)), coordinate);

        assertTrue(artifact.isPresent());
        assertArrayEquals(bytes, Files.readAllBytes(artifact.orElseThrow().cachePath()));
        assertEquals("local-overlay:maven-local", artifact.orElseThrow().source());
        assertTrue(Files.isRegularFile(artifact.orElseThrow().cachePath()));
        assertEquals(
                List.of(RepositoryCacheScope.forOverlay(
                        "MAVEN_LOCAL", "maven-local", mavenLocalRoot)),
                new ScopedPomCacheReader(cacheRoot).matchingScopes(
                        coordinate,
                        new CacheRelativePath(artifact.orElseThrow().repositoryPath()),
                        artifact.orElseThrow().source()));
    }

    @Test
    void materializesArtifactFromMavenLocalOverlayAndRecordsSource() throws IOException {
        ArtifactDescriptor descriptor = ArtifactDescriptor.jar(
                coordinate("com.example", "app", "1.0.0"),
                Optional.of("tests"));
        byte[] bytes = new byte[] {1, 2, 3};
        Path mavenLocalRoot = writeMavenLocal(pathBuilder.artifactPath(descriptor), bytes);
        Optional<CachedArtifact> artifact = materializer()
                .materializeArtifact(List.of(RepositoryOverlay.mavenLocal(mavenLocalRoot)), descriptor);

        assertTrue(artifact.isPresent());
        assertArrayEquals(bytes, Files.readAllBytes(artifact.orElseThrow().cachePath()));
        assertEquals("local-overlay:maven-local", artifact.orElseThrow().source());
        assertTrue(Files.isRegularFile(artifact.orElseThrow().cachePath()));
    }

    @Test
    void returnsEmptyWhenOverlayDoesNotContainArtifact() {
        Optional<CachedArtifact> artifact = materializer().materializePom(
                List.of(RepositoryOverlay.mavenLocal(tempDir.resolve("missing-m2"))),
                coordinate("com.example", "app", "1.0.0"));

        assertTrue(artifact.isEmpty());
    }

    private LocalOverlayMaterializer materializer() {
        return new LocalOverlayMaterializer(new LocalArtifactCache(tempDir.resolve("cache")));
    }

    private Path writeMavenLocal(String repositoryPath, byte[] bytes) throws IOException {
        Path root = tempDir.resolve("m2");
        Path path = root.resolve(repositoryPath);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        return root;
    }

    private static Coordinate coordinate(String groupId, String artifactId, String version) {
        return new Coordinate(groupId, artifactId, Optional.of(version));
    }
}
