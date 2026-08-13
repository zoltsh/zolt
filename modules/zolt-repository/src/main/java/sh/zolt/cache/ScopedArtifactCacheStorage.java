package sh.zolt.cache;

import sh.zolt.lockfile.CacheRelativePath;
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
        long size = size(blob);
        if (size == 0) {
            throw invalidBlob(blob, "is empty");
        }
        if (size != entry.length()) {
            throw invalidBlob(blob, "has length " + size + " but its index records " + entry.length());
        }
        String actualDigest = sha256(blob);
        if (!actualDigest.equals(entry.sha256())) {
            throw invalidBlob(blob, "does not match its content-addressed SHA-256 path");
        }
        return Optional.of(cached(coordinate, blob, size, actualDigest, entry.source()));
    }

    CachedArtifact store(
            RepositoryCacheScope scope,
            Coordinate coordinate,
            String mavenPath,
            RepositoryArtifact artifact) {
        try {
            if (artifact.size() == 0) {
                throw new ArtifactCacheException(
                        "Downloaded artifact " + coordinate + " is empty. The cache was not updated.");
            }
            String source = artifact.repositoryId().isBlank()
                    ? artifact.source().toString()
                    : artifact.repositoryId();
            String digest = artifact.sha256();
            Path blob = blobPath(digest, mavenPath);
            moveAtomically(artifact.temporaryPath(), blob);
            writeIndex(scope, mavenPath, new IndexEntry(digest, artifact.size(), source));
            return cached(coordinate, blob, artifact.size(), digest, source);
        } finally {
            artifact.close();
        }
    }

    CachedArtifact storeLocal(
            Coordinate coordinate,
            String mavenPath,
            String source,
            Path sourcePath) {
        long sourceSize = size(sourcePath);
        if (sourceSize == 0) {
            throw new ArtifactCacheException("Local artifact " + coordinate + " is empty.");
        }
        String digest = sha256(sourcePath);
        Path blob = blobPath(digest, mavenPath);
        copyAtomically(sourcePath, blob);
        return cached(coordinate, blob, sourceSize, digest, source);
    }

    void invalidate(RepositoryCacheScope scope, String mavenPath) {
        Path index = indexPath(scope, mavenPath);
        try {
            Files.deleteIfExists(index);
        } catch (IOException exception) {
            throw new ArtifactCacheException(
                    "Could not remove corrupt artifact cache index at " + index + ".",
                    exception);
        }
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
        } catch (IllegalArgumentException exception) {
            throw new CorruptArtifactCacheEntryException(
                    "Artifact cache index at " + path + " is invalid. Delete it and retry.",
                    exception);
        } catch (IOException exception) {
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
        return contained("indexes/" + scope.key() + "/" + mavenPath + ".idx");
    }

    private Path blobPath(String digest, String mavenPath) {
        Path fileName = Path.of(mavenPath).getFileName();
        if (fileName == null) {
            throw new ArtifactCacheException("Refusing artifact cache path without a file name.");
        }
        return contained("blobs/v2/sha256/" + digest + "/" + fileName);
    }

    private Path contained(String relativePath) {
        try {
            return new CacheRelativePath(relativePath).resolveWithin(root);
        } catch (IllegalArgumentException exception) {
            throw new ArtifactCacheException("Refusing artifact cache path outside the configured cache root.");
        }
    }

    private CachedArtifact cached(
            Coordinate coordinate,
            Path blob,
            long size,
            String sha256,
            String source) {
        Path relative = root.toAbsolutePath().normalize().relativize(blob.toAbsolutePath().normalize());
        String repositoryPath = relative.toString().replace('\\', '/');
        return new CachedArtifact(coordinate, repositoryPath, blob, size, sha256, source);
    }

    private static String value(String line, String key, Path path) {
        String prefix = key + "=";
        if (!line.startsWith(prefix)) {
            throw invalidIndex(path);
        }
        return line.substring(prefix.length());
    }

    private static CorruptArtifactCacheEntryException invalidIndex(Path path) {
        return new CorruptArtifactCacheEntryException(
                "Artifact cache index at " + path + " is invalid. Delete it and retry.");
    }

    private static CorruptArtifactCacheEntryException invalidBlob(Path path, String detail) {
        return new CorruptArtifactCacheEntryException(
                "Cached artifact at " + path + " " + detail + ". Delete it and run the command again.");
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new ArtifactCacheException(
                    "Could not inspect cached artifact at " + path + ". Check filesystem permissions.",
                    exception);
        }
    }

    private static String sha256(Path path) {
        try (var input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new ArtifactCacheException("Could not hash cached artifact at " + path + ".", exception);
        }
    }

    private static void moveAtomically(Path source, Path path) {
        Path directory = path.getParent();
        try {
            Files.createDirectories(directory);
            Files.move(source, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(source);
            } catch (IOException ignored) {
            }
            throw new ArtifactCacheException(
                    "Could not move downloaded artifact into cache at " + path + ".",
                    exception);
        }
    }

    private static void copyAtomically(Path source, Path path) {
        Path directory = path.getParent();
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, path.getFileName().toString(), ".tmp");
            try {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                try (var channel = java.nio.channels.FileChannel.open(
                        temporary, java.nio.file.StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
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
