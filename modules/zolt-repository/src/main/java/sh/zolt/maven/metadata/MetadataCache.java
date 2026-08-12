package sh.zolt.maven.metadata;

import sh.zolt.maven.repository.RepositoryAccess;
import sh.zolt.maven.MavenRepositoryValue;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Caches {@code maven-metadata.xml} version listings in a namespace kept strictly separate from the
 * immutable artifact cache. Each unauthenticated repository is namespaced by a SHA-256 digest over
 * its exact ID and canonical URI, preventing unrelated local names and URLs from aliasing. Version
 * listings are mutable, so they must never enter the artifact cache. Writes are atomic (temp file +
 * atomic move). Authenticated repository views are intentionally not cacheable.
 */
public final class MetadataCache {
    private static final String METADATA_DIR = "metadata";
    private static final String FILE_NAME = "maven-metadata.xml";
    private static final String FETCHED_SUFFIX = ".fetched";

    private final Path root;
    private final Clock clock;

    public MetadataCache(Path cacheRoot) {
        this(cacheRoot, Clock.systemUTC());
    }

    public MetadataCache(Path cacheRoot, Clock clock) {
        this.root = Objects.requireNonNull(cacheRoot, "cacheRoot");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<byte[]> read(RepositoryAccess repository, String groupId, String artifactId) {
        Path file = metadataFile(repository, groupId, artifactId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            return bytes.length == 0 ? Optional.empty() : Optional.of(bytes);
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    public Optional<Instant> fetchedAt(RepositoryAccess repository, String groupId, String artifactId) {
        Path sidecar = fetchedSidecar(repository, groupId, artifactId);
        if (!Files.isRegularFile(sidecar)) {
            return Optional.empty();
        }
        try {
            String value = Files.readString(sidecar, StandardCharsets.UTF_8).trim();
            return value.isBlank() ? Optional.empty() : Optional.of(Instant.parse(value));
        } catch (IOException | DateTimeParseException exception) {
            return Optional.empty();
        }
    }

    public void write(RepositoryAccess repository, String groupId, String artifactId, byte[] bytes) {
        writeAtomically(metadataFile(repository, groupId, artifactId), bytes);
        writeAtomically(
                fetchedSidecar(repository, groupId, artifactId),
                clock.instant().toString().getBytes(StandardCharsets.UTF_8));
    }

    private Path metadataFile(RepositoryAccess repository, String groupId, String artifactId) {
        requireCacheable(repository);
        String safeGroup = MavenRepositoryValue.groupId(groupId);
        String safeArtifact = MavenRepositoryValue.artifactId(artifactId);
        Path namespaceRoot = root.toAbsolutePath().normalize()
                .resolve(METADATA_DIR)
                .resolve("v2")
                .resolve(namespace(repository));
        Path file = namespaceRoot
                .resolve(safeGroup.replace('.', '/'))
                .resolve(safeArtifact)
                .resolve(FILE_NAME)
                .normalize();
        if (!file.startsWith(namespaceRoot)) {
            throw new MetadataCacheException("Refusing metadata cache path outside its repository namespace.");
        }
        return file;
    }

    private Path fetchedSidecar(RepositoryAccess repository, String groupId, String artifactId) {
        Path file = metadataFile(repository, groupId, artifactId);
        return file.resolveSibling(file.getFileName().toString() + FETCHED_SUFFIX);
    }

    private static String namespace(RepositoryAccess repository) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "zolt-metadata-cache-v2");
            update(digest, Objects.requireNonNull(repository.id(), "repository.id"));
            update(digest, canonicalUri(repository.uri()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for metadata cache identity.", exception);
        }
    }

    private static String canonicalUri(URI uri) {
        URI normalized = Objects.requireNonNull(uri, "repository.uri").normalize();
        String rawScheme = normalized.getScheme();
        String rawHost = normalized.getHost();
        if (rawScheme == null || rawHost == null) {
            throw new IllegalArgumentException("Repository URI must include a scheme and host: " + uri + ".");
        }
        String scheme = rawScheme.toLowerCase(Locale.ROOT);
        String host = rawHost.toLowerCase(Locale.ROOT);
        int port = normalized.getPort();
        if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) {
            port = -1;
        }
        String path = normalized.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        } else if (!path.endsWith("/")) {
            path = path + "/";
        }
        try {
            return new URI(scheme, null, host, port, path, normalized.getRawQuery(), normalized.getRawFragment())
                    .toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Repository URI cannot be canonicalized: " + uri + ".", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void requireCacheable(RepositoryAccess repository) {
        Objects.requireNonNull(repository, "repository");
        if (repository.authentication().isPresent()) {
            throw new IllegalArgumentException("Authenticated repository metadata is not cacheable.");
        }
    }

    private static void writeAtomically(Path path, byte[] bytes) {
        Path directory = path.getParent();
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, path.getFileName().toString(), ".tmp");
            try {
                Files.write(temporary, bytes);
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                Files.deleteIfExists(temporary);
                throw exception;
            }
        } catch (IOException exception) {
            throw new MetadataCacheException(
                    "Could not write cached version listing at " + path + ". Check filesystem permissions.",
                    exception);
        }
    }
}
