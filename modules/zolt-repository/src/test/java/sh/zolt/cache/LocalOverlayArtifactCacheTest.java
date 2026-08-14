package sh.zolt.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.lockfile.CacheRelativePath;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParser;

final class LocalOverlayArtifactCacheTest {
    private final CoordinateParser parser = new CoordinateParser();

    @TempDir
    private Path tempDir;

    @Test
    void materializesPomWithScopedProvenance() throws Exception {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        Path source = tempDir.resolve("local-guava.pom");
        Files.writeString(source, "<project/>");
        RepositoryCacheScope scope = RepositoryCacheScope.of("test local overlay");

        CachedArtifact artifact = cache.materializeOverlayPom(scope, coordinate, "local-m2", source);

        assertTrue(artifact.repositoryPath().startsWith("blobs/v2/sha256/"));
        assertTrue(artifact.repositoryPath().endsWith("/guava-33.4.0-jre.pom"));
        assertEquals("local-overlay:local-m2", artifact.source());
        assertEquals(tempDir.resolve(artifact.repositoryPath()), artifact.cachePath());
        assertArrayEquals("<project/>".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(artifact.cachePath()));
        assertEquals(
                List.of(scope),
                new ScopedPomCacheReader(tempDir).matchingScopes(
                        coordinate,
                        new CacheRelativePath(artifact.repositoryPath()),
                        artifact.source()));
    }

    @Test
    void overlayNamespaceCannotEscapeTheArtifactCacheRoot() throws Exception {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.example:demo:1.0.0");
        Path source = tempDir.resolve("demo.pom");
        Files.writeString(source, "<project/>");
        Path outside = tempDir.resolveSibling("outside");

        assertThrows(
                ArtifactCacheException.class,
                () -> cache.materializeOverlayPom(
                        RepositoryCacheScope.of("test local overlay"),
                        coordinate,
                        "../../outside",
                        source));

        assertFalse(Files.exists(outside));
    }

    @Test
    void contentAddressedCacheCannotEscapeThroughSymlink() throws Exception {
        Path cacheRoot = Files.createDirectory(tempDir.resolve("cache"));
        LocalArtifactCache cache = new LocalArtifactCache(cacheRoot);
        Coordinate coordinate = parser.parse("com.example:demo:1.0.0");
        Path source = tempDir.resolve("demo.pom");
        Files.writeString(source, "<project/>");
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        try {
            Files.createSymbolicLink(cacheRoot.resolve("blobs"), outside);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }

        assertThrows(
                ArtifactCacheException.class,
                () -> cache.materializeOverlayPom(
                        RepositoryCacheScope.of("test local overlay"), coordinate, "local", source));
        try (var files = Files.list(outside)) {
            assertEquals(List.of(), files.toList());
        }
    }

    @Test
    void materializesClassifierArtifactIntoContentAddressedCachePath() throws Exception {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("io.quarkus:quarkus-custom-deployment:1.0.0");
        ArtifactDescriptor descriptor = ArtifactDescriptor.jar(coordinate, Optional.of("deployment"));
        Path source = tempDir.resolve("deployment.jar");
        byte[] bytes = new byte[] {0x50, 0x4b, 0x03, 0x04};
        Files.write(source, bytes);

        CachedArtifact artifact = cache.materializeOverlayArtifact(
                RepositoryCacheScope.of("test local overlay"), descriptor, "local-m2", source);

        assertTrue(artifact.repositoryPath().startsWith("blobs/v2/sha256/"));
        assertTrue(artifact.repositoryPath().endsWith("/quarkus-custom-deployment-1.0.0-deployment.jar"));
        assertArrayEquals(bytes, Files.readAllBytes(artifact.cachePath()));
    }

    @Test
    void emptyArtifactFailsWithActionableRemediation() throws Exception {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        Path source = tempDir.resolve("empty.pom");
        Files.write(source, new byte[0]);

        ArtifactCacheException exception = assertThrows(
                ArtifactCacheException.class,
                () -> cache.materializeOverlayPom(
                        RepositoryCacheScope.of("test local overlay"), coordinate, "local-m2", source));

        assertTrue(exception.getMessage().contains("Local repository overlay POM"));
        assertTrue(exception.getMessage().contains("Reinstall the artifact locally or remove it"));
    }

    @Test
    void sourceReadFailureIsActionable() throws Exception {
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        Path directorySource = tempDir.resolve("source-directory");
        Files.createDirectories(directorySource);

        ArtifactCacheException exception = assertThrows(
                ArtifactCacheException.class,
                () -> cache.materializeOverlayPom(
                        RepositoryCacheScope.of("test local overlay"),
                        coordinate,
                        "local-m2",
                        directorySource));

        assertTrue(exception.getMessage().contains("Local repository overlay POM"));
        assertTrue(exception.getMessage().contains("is missing at " + directorySource));
        assertTrue(exception.getMessage().contains("Reinstall the artifact locally or remove it"));
    }
}
