package sh.zolt.cache;

import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.RepositoryArtifact;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Content-addressed artifact blobs plus repository-configuration-scoped Maven-path indexes. */
final class ScopedArtifactCacheStorage {
    private static final String INDEX_VERSION = "1";
    private final Path root;

    ScopedArtifactCacheStorage(Path root) {
        this.root = root;
    }

    boolean contains(RepositoryCacheScope scope, String mavenPath) {
        Optional<IndexEntry> entry = readIndex(scope, mavenPath);
        return entry.isPresent() && Files.isRegularFile(blobPath(entry.orElseThrow().sha256(), mavenPath));
    }

    Optional<CachedArtifact> find(
            RepositoryCacheScope scope,
            Coordinate coordinate,
            String mavenPath) {
        Optional<IndexEntry> indexed = readIndex(scope, mavenPath);
        if (indexed.isEmpty()) {
            return Optional.empty();
        }
        IndexEntry entry = indexed.orElseThrow();
        Path blob = blobPath(entry.sha256(), mavenPath);
        if (!Files.isRegularFile(blob)) {
            return Optional.empty();
        }
        byte[] bytes = read(blob);
        if (bytes.length == 0) {
            throw invalidBlob(blob, "is empty");
        }
        if (bytes.length != entry.length()) {
            throw invalidBlob(blob, "has length " + bytes.length + " but its index records " + entry.length());
        }
        String actualDigest = sha256(bytes);
        if (!actualDigest.equals(entry.sha256())) {
            throw invalidBlob(blob, "does not match its content-addressed SHA-256 path");
        }
        return Optional.of(cached(coordinate, blob, bytes, entry.source()));
    }

    CachedArtifact store(
            RepositoryCacheScope scope,
            Coordinate coordinate,
            String mavenPath,
            RepositoryArtifact artifact) {
        byte[] bytes = artifact.bytes();
        if (bytes.length == 0) {
            throw new ArtifactCacheException(
                    "Downloaded artifact " + coordinate + " is empty. The cache was not updated.");
        }
        String source = artifact.repositoryId().isBlank()
                ? artifact.source().toString()
                : artifact.repositoryId();
        String digest = sha256(bytes);
        Path blob = blobPath(digest, mavenPath);
        writeAtomically(blob, bytes);
        writeIndex(scope, mavenPath, new IndexEntry(digest, bytes.length, source));
        return cached(coordinate, blob, bytes, source);
    }

    CachedArtifact storeLocal(
            Coordinate coordinate,
            String mavenPath,
            String source,
            byte[] bytes) {
        if (bytes.length == 0) {
            throw new ArtifactCacheException("Local artifact " + coordinate + " is empty.");
        }
        Path blob = blobPath(sha256(bytes), mavenPath);
        writeAtomically(blob, bytes);
        return cached(coordinate, blob, bytes, source);
    }

    private Optional<IndexEntry> readIndex(RepositoryCacheScope scope, String mavenPath) {
        Path path = indexPath(scope, mavenPath);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(path)) {
            throw new ArtifactCacheException("Artifact cache index at " + path + " is not a regular file.");
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.size() != 4 || !lines.get(0).equals("version=" + INDEX_VERSION)) {
                throw invalidIndex(path);
            }
            String digest = value(lines.get(1), "sha256", path);
            if (!digest.matches("[0-9a-f]{64}")) {
                throw invalidIndex(path);
            }
            long length = Long.parseLong(value(lines.get(2), "length", path));
            if (length < 1) {
                throw invalidIndex(path);
            }
            String source = new String(
                    Base64.getUrlDecoder().decode(value(lines.get(3), "source", path)),
                    StandardCharsets.UTF_8);
            if (source.isBlank()) {
                throw invalidIndex(path);
            }
            return Optional.of(new IndexEntry(digest, length, source));
        } catch (IOException | IllegalArgumentException exception) {
            throw new ArtifactCacheException(
                    "Could not read artifact cache index at " + path + ". Delete it and retry.",
                    exception);
        }
    }

    private void writeIndex(RepositoryCacheScope scope, String mavenPath, IndexEntry entry) {
        String source = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(entry.source().getBytes(StandardCharsets.UTF_8));
        String content = String.join(
                "\n",
                "version=" + INDEX_VERSION,
                "sha256=" + entry.sha256(),
                "length=" + entry.length(),
                "source=" + source) + "\n";
        writeAtomically(indexPath(scope, mavenPath), content.getBytes(StandardCharsets.UTF_8));
    }

    private Path indexPath(RepositoryCacheScope scope, String mavenPath) {
        return contained(root.resolve("indexes").resolve(scope.key()).resolve(mavenPath + ".idx"));
    }

    private Path blobPath(String digest, String mavenPath) {
        Path fileName = Path.of(mavenPath).getFileName();
        if (fileName == null) {
            throw new ArtifactCacheException("Refusing artifact cache path without a file name.");
        }
        return contained(root.resolve("blobs").resolve("v2").resolve("sha256").resolve(digest).resolve(fileName));
    }

    private Path contained(Path candidate) {
        Path cacheRoot = root.toAbsolutePath().normalize();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(cacheRoot) || normalized.equals(cacheRoot)) {
            throw new ArtifactCacheException("Refusing artifact cache path outside the configured cache root.");
        }
        return root.isAbsolute() ? normalized : candidate.normalize();
    }

    private CachedArtifact cached(Coordinate coordinate, Path blob, byte[] bytes, String source) {
        Path relative = root.toAbsolutePath().normalize().relativize(blob.toAbsolutePath().normalize());
        String repositoryPath = relative.toString().replace('\\', '/');
        return new CachedArtifact(coordinate, repositoryPath, blob, bytes, source);
    }

    private static String value(String line, String key, Path path) {
        String prefix = key + "=";
        if (!line.startsWith(prefix)) {
            throw invalidIndex(path);
        }
        return line.substring(prefix.length());
    }

    private static ArtifactCacheException invalidIndex(Path path) {
        return new ArtifactCacheException(
                "Artifact cache index at " + path + " is invalid. Delete it and retry.");
    }

    private static ArtifactCacheException invalidBlob(Path path, String detail) {
        return new ArtifactCacheException(
                "Cached artifact at " + path + " " + detail + ". Delete it and run the command again.");
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new ArtifactCacheException(
                    "Could not read cached artifact at " + path + ". Check filesystem permissions.",
                    exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new ArtifactCacheException("Could not cache artifact because SHA-256 is unavailable.", exception);
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
            throw new ArtifactCacheException(
                    "Could not write cached artifact at " + path + ". Check filesystem permissions.",
                    exception);
        }
    }

    private record IndexEntry(String sha256, long length, String source) {
    }
}
