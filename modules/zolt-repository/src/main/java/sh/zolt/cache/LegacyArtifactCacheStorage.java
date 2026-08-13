package sh.zolt.cache;

import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.RepositoryArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Read compatibility for the unattributed Maven-path cache layout that predates scoped indexes. */
final class LegacyArtifactCacheStorage {
    private final Path root;
    private final DownloadCoordinator downloadCoordinator;

    LegacyArtifactCacheStorage(Path root, DownloadCoordinator downloadCoordinator) {
        this.root = root;
        this.downloadCoordinator = downloadCoordinator;
    }

    Path path(String repositoryPath) {
        Path cacheRoot = root.toAbsolutePath().normalize();
        Path resolved = root.resolve(repositoryPath).normalize();
        Path absoluteResolved = resolved.toAbsolutePath().normalize();
        if (!absoluteResolved.startsWith(cacheRoot) || absoluteResolved.equals(cacheRoot)) {
            throw new ArtifactCacheException("Refusing artifact cache path outside the configured cache root.");
        }
        return resolved;
    }

    CachedArtifact getOrFetch(Coordinate coordinate, String repositoryPath, ArtifactFetcher fetcher) {
        Path cachePath = path(repositoryPath);
        if (Files.isRegularFile(cachePath)) {
            byte[] bytes = read(cachePath);
            if (bytes.length > 0) {
                return new CachedArtifact(coordinate, repositoryPath, cachePath, bytes, "legacy-cache");
            }
            throw empty(cachePath);
        }

        return downloadCoordinator.run(repositoryPath, () -> {
            RepositoryArtifact artifact = fetcher.fetch(coordinate);
            byte[] bytes = artifact.bytes();
            if (bytes.length == 0) {
                throw new ArtifactCacheException(
                        "Downloaded artifact " + coordinate + " is empty. The cache was not updated.");
            }
            writeAtomically(cachePath, bytes);
            String source = artifact.repositoryId().isBlank()
                    ? artifact.source().toString()
                    : artifact.repositoryId();
            return new CachedArtifact(coordinate, repositoryPath, cachePath, bytes, source);
        });
    }

    CachedArtifact getCached(Coordinate coordinate, String repositoryPath, String artifactKind) {
        Path cachePath = path(repositoryPath);
        if (!Files.isRegularFile(cachePath)) {
            throw new ArtifactCacheException(
                    "Offline mode requires cached "
                            + artifactKind
                            + " for "
                            + coordinate
                            + " at "
                            + cachePath
                            + ". Run the command without --offline to download it, then retry with --offline.");
        }
        byte[] bytes = read(cachePath);
        if (bytes.length == 0) {
            throw empty(cachePath);
        }
        return new CachedArtifact(coordinate, repositoryPath, cachePath, bytes, "legacy-cache");
    }

    static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new ArtifactCacheException(
                    "Could not read cached artifact at " + path + ". Check filesystem permissions.",
                    exception);
        }
    }

    private static ArtifactCacheException empty(Path path) {
        return new ArtifactCacheException(
                "Cached artifact at " + path + " is empty. Delete it and run the command again.");
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
}
