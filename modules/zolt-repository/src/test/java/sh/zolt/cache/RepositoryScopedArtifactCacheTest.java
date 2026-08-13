package sh.zolt.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.MavenRepositoryPathBuilder;
import sh.zolt.maven.repository.RepositoryArtifact;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RepositoryScopedArtifactCacheTest {
    private static final Coordinate LIB =
            new Coordinate("com.example", "lib", Optional.of("1.0.0"));

    @TempDir
    private Path tempDir;

    @Test
    void separatesSameMavenPathByRepositoryConfigurationAndPersistsSelectedSource() {
        RepositoryCacheScope privateScope = RepositoryCacheScope.of("private first");
        RepositoryCacheScope publicScope = RepositoryCacheScope.of("public first");
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);

        CachedArtifact privateArtifact = cache.getOrFetchJar(
                privateScope,
                LIB,
                ignored -> artifact("private", "private bytes"));
        CachedArtifact publicArtifact = cache.getOrFetchJar(
                publicScope,
                LIB,
                ignored -> artifact("public", "public bytes"));

        assertEquals("private", privateArtifact.source());
        assertEquals("public", publicArtifact.source());
        assertNotEquals(privateArtifact.repositoryPath(), publicArtifact.repositoryPath());
        assertArrayEquals(bytes("private bytes"), privateArtifact.bytes());
        assertArrayEquals(bytes("public bytes"), publicArtifact.bytes());
        assertTrue(privateArtifact.repositoryPath().startsWith("blobs/v2/sha256/"));
        assertEquals("lib-1.0.0.jar", privateArtifact.cachePath().getFileName().toString());

        LocalArtifactCache nextInvocation = new LocalArtifactCache(tempDir);
        AtomicInteger unexpectedFetches = new AtomicInteger();
        CachedArtifact cached = nextInvocation.getOrFetchJar(privateScope, LIB, ignored -> {
            unexpectedFetches.incrementAndGet();
            return artifact("wrong", "wrong bytes");
        });

        assertEquals(0, unexpectedFetches.get());
        assertEquals("private", cached.source());
        assertArrayEquals(privateArtifact.bytes(), cached.bytes());
    }

    @Test
    void scopedLookupInvalidatesRatherThanTrustingAnUnattributedLegacyEntry() throws Exception {
        MavenRepositoryPathBuilder paths = new MavenRepositoryPathBuilder();
        Path legacy = tempDir.resolve(paths.jarPath(LIB));
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, bytes("legacy bytes"));
        AtomicInteger fetches = new AtomicInteger();

        CachedArtifact artifact = new LocalArtifactCache(tempDir).getOrFetchJar(
                RepositoryCacheScope.of("private repository"),
                LIB,
                ignored -> {
                    fetches.incrementAndGet();
                    return artifact("private", "trusted bytes");
                });

        assertEquals(1, fetches.get());
        assertEquals("private", artifact.source());
        assertArrayEquals(bytes("trusted bytes"), artifact.bytes());
        assertArrayEquals(bytes("legacy bytes"), Files.readAllBytes(legacy));
    }

    @Test
    void offlineLookupUsesTheExactScopedBlobAndSource() {
        RepositoryCacheScope scope = RepositoryCacheScope.of("private repository");
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        CachedArtifact online = cache.getOrFetchJar(
                scope,
                LIB,
                ignored -> artifact("private", "trusted bytes"));

        CachedArtifact offline = new LocalArtifactCache(tempDir).getCachedJar(scope, LIB);

        assertEquals(online.repositoryPath(), offline.repositoryPath());
        assertEquals("private", offline.source());
        assertArrayEquals(online.bytes(), offline.bytes());
    }

    @Test
    void onlineLookupRepairsCorruptBlobWhileOfflineFailsClosed() throws Exception {
        RepositoryCacheScope scope = RepositoryCacheScope.of("private repository");
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);
        CachedArtifact original = cache.getOrFetchJar(
                scope,
                LIB,
                ignored -> artifact("private", "trusted bytes"));
        Files.writeString(original.cachePath(), "corrupt bytes");

        ArtifactCacheException offline = assertThrows(
                ArtifactCacheException.class,
                () -> new LocalArtifactCache(tempDir).getCachedJar(scope, LIB));
        assertTrue(offline.getMessage().contains("Offline mode found corrupt cached JAR"));
        assertTrue(offline.getMessage().contains("Run the command without --offline to repair it"));

        AtomicInteger fetches = new AtomicInteger();
        CachedArtifact repaired = new LocalArtifactCache(tempDir).getOrFetchJar(scope, LIB, ignored -> {
            fetches.incrementAndGet();
            return artifact("private", "trusted bytes");
        });

        assertEquals(1, fetches.get());
        assertArrayEquals(bytes("trusted bytes"), repaired.bytes());
        assertArrayEquals(bytes("trusted bytes"), Files.readAllBytes(repaired.cachePath()));
    }

    private static RepositoryArtifact artifact(String repositoryId, String content) {
        return new RepositoryArtifact(
                LIB,
                "com/example/lib/1.0.0/lib-1.0.0.jar",
                URI.create("https://" + repositoryId + ".example/lib.jar"),
                repositoryId,
                bytes(content));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
