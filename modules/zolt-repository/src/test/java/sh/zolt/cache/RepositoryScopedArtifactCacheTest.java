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
import java.util.List;
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
        assertArrayEquals(bytes("private bytes"), cachedBytes(privateArtifact));
        assertArrayEquals(bytes("public bytes"), cachedBytes(publicArtifact));
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
        assertArrayEquals(cachedBytes(privateArtifact), cachedBytes(cached));
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
        assertArrayEquals(bytes("trusted bytes"), cachedBytes(artifact));
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
        assertArrayEquals(cachedBytes(online), cachedBytes(offline));
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
        assertArrayEquals(bytes("trusted bytes"), cachedBytes(repaired));
        assertArrayEquals(bytes("trusted bytes"), Files.readAllBytes(repaired.cachePath()));
    }

    @Test
    void reportsActualDownloadHitAndRepairOutcomes() throws Exception {
        RepositoryCacheScope scope = RepositoryCacheScope.of("outcome repository");
        LocalArtifactCache cache = new LocalArtifactCache(tempDir);

        CacheLookupResult downloaded = cache.getOrFetchJarResult(
                scope,
                LIB,
                (ignored, downloadDirectory) -> artifact("private", "trusted bytes"));
        CacheLookupResult hit = cache.getOrFetchJarResult(
                scope,
                LIB,
                (ignored, downloadDirectory) -> {
                    throw new AssertionError("cache hit must not fetch");
                });
        Files.writeString(indexPath(tempDir, scope), "version=1\n");
        CacheLookupResult repaired = cache.getOrFetchJarResult(
                scope,
                LIB,
                (ignored, downloadDirectory) -> artifact("private", "trusted bytes"));

        assertEquals(CacheOutcome.DOWNLOADED, downloaded.outcome());
        assertEquals(CacheOutcome.HIT, hit.outcome());
        assertEquals(CacheOutcome.REPAIRED, repaired.outcome());
    }

    @Test
    void onlineRepairsEveryMalformedIndexAndBlobCaseWhileOfflineFailsClosed() throws Exception {
        List<CorruptionCase> cases = List.of(
                new CorruptionCase(
                        "truncated-index",
                        (index, blob) -> Files.writeString(index, "version=1\n"),
                        "is invalid"),
                new CorruptionCase(
                        "index-directory",
                        (index, blob) -> {
                            Files.delete(index);
                            Files.createDirectory(index);
                        },
                        "is not a regular file"),
                new CorruptionCase(
                        "invalid-digest",
                        (index, blob) -> replaceIndexLine(index, "sha256=", "sha256=invalid"),
                        "is invalid"),
                new CorruptionCase(
                        "invalid-length",
                        (index, blob) -> replaceIndexLine(index, "length=", "length=0"),
                        "is invalid"),
                new CorruptionCase(
                        "invalid-source-base64",
                        (index, blob) -> replaceIndexLine(index, "source=", "source=%%%"),
                        "is invalid"),
                new CorruptionCase(
                        "invalid-index-utf8",
                        (index, blob) -> Files.write(index, new byte[] {(byte) 0xc3, 0x28}),
                        "contains invalid UTF-8"),
                new CorruptionCase(
                        "missing-blob",
                        (index, blob) -> Files.delete(blob),
                        "is missing"),
                new CorruptionCase(
                        "blob-directory",
                        (index, blob) -> {
                            Files.delete(blob);
                            Files.createDirectory(blob);
                            Files.writeString(blob.resolve("unexpected-entry"), "corrupt");
                        },
                        "is not a regular file"),
                new CorruptionCase(
                        "blob-length-mismatch",
                        (index, blob) -> replaceIndexLine(index, "length=", "length=999"),
                        "has length"),
                new CorruptionCase(
                        "blob-digest-mismatch",
                        (index, blob) -> Files.writeString(blob, "altered bytes"),
                        "does not match its content-addressed SHA-256 path"));

        for (CorruptionCase corruption : cases) {
            Path root = tempDir.resolve(corruption.name());
            RepositoryCacheScope scope = RepositoryCacheScope.of("matrix repository");
            CachedArtifact original = new LocalArtifactCache(root).getOrFetchJar(
                    scope,
                    LIB,
                    ignored -> artifact("private", "trusted bytes"));
            Path index = indexPath(root, scope);
            corruption.mutator().apply(index, original.cachePath());

            ArtifactCacheException offline = assertThrows(
                    ArtifactCacheException.class,
                    () -> new LocalArtifactCache(root).getCachedJar(scope, LIB),
                    corruption.name());
            assertTrue(offline.getMessage().contains("Offline mode found corrupt cached JAR"), corruption.name());
            assertTrue(offline.getMessage().contains(corruption.diagnostic()), offline.getMessage());
            assertTrue(offline.getMessage().contains("Run the command without --offline to repair it"));

            AtomicInteger fetches = new AtomicInteger();
            CacheLookupResult repaired = new LocalArtifactCache(root).getOrFetchJarResult(
                    scope,
                    LIB,
                    (ignored, downloadDirectory) -> {
                        fetches.incrementAndGet();
                        return artifact("private", "trusted bytes");
                    });

            assertEquals(1, fetches.get(), corruption.name());
            assertEquals(CacheOutcome.REPAIRED, repaired.outcome(), corruption.name());
            assertArrayEquals(bytes("trusted bytes"), Files.readAllBytes(repaired.artifact().cachePath()));
            assertArrayEquals(
                    bytes("trusted bytes"),
                    Files.readAllBytes(new LocalArtifactCache(root).getCachedJar(scope, LIB).cachePath()));
        }
    }

    private static RepositoryArtifact artifact(String repositoryId, String content) {
        return new RepositoryArtifact(
                LIB,
                "com/example/lib/1.0.0/lib-1.0.0.jar",
                URI.create("https://" + repositoryId + ".example/lib.jar"),
                repositoryId,
                bytes(content));
    }

    private static Path indexPath(Path root, RepositoryCacheScope scope) {
        String mavenPath = new MavenRepositoryPathBuilder().jarPath(LIB);
        return root.resolve("indexes").resolve(scope.key()).resolve(mavenPath + ".idx");
    }

    private static void replaceIndexLine(Path index, String prefix, String replacement) throws Exception {
        List<String> lines = Files.readAllLines(index).stream()
                .map(line -> line.startsWith(prefix) ? replacement : line)
                .toList();
        Files.write(index, lines);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] cachedBytes(CachedArtifact artifact) {
        try {
            return Files.readAllBytes(artifact.cachePath());
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private record CorruptionCase(
            String name,
            CacheCorruption mutator,
            String diagnostic) {}

    @FunctionalInterface
    private interface CacheCorruption {
        void apply(Path index, Path blob) throws Exception;
    }
}
