package sh.zolt.maven.metadata;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.maven.CoordinateParseException;
import sh.zolt.maven.repository.RepositoryAccess;
import sh.zolt.maven.repository.RepositoryAuthentication;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MetadataCacheTest {
    private static final byte[] LISTING =
            "<metadata><versioning><versions><version>1.0.0</version></versions></versioning></metadata>"
                    .getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path cacheRoot;

    @Test
    void writesUnderSeparateMetadataNamespace() {
        new MetadataCache(cacheRoot).write(access("central"), "com.google.guava", "guava", LISTING);

        try (var files = Files.walk(cacheRoot.resolve("metadata/v2"))) {
            assertTrue(files.anyMatch(path -> path.endsWith("com/google/guava/guava/maven-metadata.xml")));
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    @Test
    void roundTripsListingBytes() {
        MetadataCache cache = new MetadataCache(cacheRoot);
        cache.write(access("central"), "com.example", "lib", LISTING);

        Optional<byte[]> read = cache.read(access("central"), "com.example", "lib");
        assertTrue(read.isPresent());
        assertArrayEquals(LISTING, read.orElseThrow());
    }

    @Test
    void readReturnsEmptyWhenAbsent() {
        assertFalse(new MetadataCache(cacheRoot).read(access("central"), "com.example", "absent").isPresent());
    }

    @Test
    void recordsFetchedTimestampSidecar() {
        Instant now = Instant.parse("2026-07-23T12:00:00Z");
        MetadataCache cache = new MetadataCache(cacheRoot, Clock.fixed(now, ZoneOffset.UTC));
        cache.write(access("central"), "com.example", "lib", LISTING);

        assertEquals(Optional.of(now), cache.fetchedAt(access("central"), "com.example", "lib"));
    }

    @Test
    void isolatesRepositories() {
        MetadataCache cache = new MetadataCache(cacheRoot);
        cache.write(access("alpha"), "com.example", "lib", LISTING);

        assertTrue(cache.read(access("alpha"), "com.example", "lib").isPresent());
        assertFalse(cache.read(access("zeta"), "com.example", "lib").isPresent());
    }

    @Test
    void canonicalizesEquivalentRepositoryUris() {
        MetadataCache cache = new MetadataCache(cacheRoot);
        RepositoryAccess first = access(
                "central", URI.create("https://CENTRAL.example.test:443/a/../maven"));
        RepositoryAccess second = access(
                "central", URI.create("https://central.example.test/maven/"));

        cache.write(first, "com.example", "lib", LISTING);

        assertTrue(cache.read(second, "com.example", "lib").isPresent());
    }

    @Test
    void rejectsAuthenticatedRepositoryCacheAccess() {
        RepositoryAccess authenticated = new RepositoryAccess(
                "private",
                URI.create("https://private.example.test/maven"),
                Optional.of(RepositoryAuthentication.bearer("secret")));

        assertThrows(
                IllegalArgumentException.class,
                () -> new MetadataCache(cacheRoot).write(authenticated, "com.example", "lib", LISTING));
    }

    @Test
    void rejectsCoordinateEscapesWithoutWritingOutsideTheCacheRoot() throws java.io.IOException {
        MetadataCache cache = new MetadataCache(cacheRoot);
        Path sibling = cacheRoot.resolveSibling("outside");

        assertThrows(
                CoordinateParseException.class,
                () -> cache.write(access("central"), "../../outside", "probe", LISTING));
        assertThrows(
                CoordinateParseException.class,
                () -> cache.write(access("central"), "com.example", "../outside", LISTING));
        assertThrows(
                CoordinateParseException.class,
                () -> cache.write(access("central"), "/absolute/path", "probe", LISTING));

        assertFalse(Files.exists(sibling));
        try (var files = Files.walk(cacheRoot)) {
            assertEquals(1, files.count(), "rejected coordinates must not create cache files");
        }
    }

    private static RepositoryAccess access(String id) {
        return access(id, URI.create("https://" + id + ".example.test/maven"));
    }

    private static RepositoryAccess access(String id, URI uri) {
        return new RepositoryAccess(id, uri, Optional.empty());
    }
}
