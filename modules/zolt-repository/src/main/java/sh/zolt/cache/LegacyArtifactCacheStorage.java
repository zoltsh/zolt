package sh.zolt.cache;

import sh.zolt.lockfile.CacheRelativePath;
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
        try {
            return new CacheRelativePath(repositoryPath).resolveWithin(root);
        } catch (IllegalArgumentException exception) {
            throw new ArtifactCacheException("Refusing artifact cache path outside the configured cache root.");
        }
    }

    CachedArtifact getOrFetch(
            Coordinate coordinate,
            String repositoryPath,
            StreamingArtifactFetcher fetcher,
            Path downloadDirectory) {
        Path cachePath = path(repositoryPath);
        if (Files.isRegularFile(cachePath)) {
            long size = size(cachePath);
            if (size > 0) {
                return cached(coordinate, repositoryPath, cachePath, "legacy-cache");
            }
            throw empty(cachePath);
        }

        return downloadCoordinator.run(repositoryPath, () -> {
            RepositoryArtifact artifact = fetcher.fetch(coordinate, downloadDirectory);
            try {
                if (artifact.size() == 0) {
                    throw new ArtifactCacheException(
                            "Downloaded artifact " + coordinate + " is empty. The cache was not updated.");
                }
                moveAtomically(artifact.temporaryPath(), cachePath);
                String source = artifact.repositoryId().isBlank()
                        ? artifact.source().toString()
                        : artifact.repositoryId();
                return new CachedArtifact(
                        coordinate,
                        repositoryPath,
                        cachePath,
                        artifact.size(),
                        artifact.sha256(),
                        source);
            } finally {
                artifact.close();
            }
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
        if (size(cachePath) == 0) {
            throw empty(cachePath);
        }
        return cached(coordinate, repositoryPath, cachePath, "legacy-cache");
    }

    private static ArtifactCacheException empty(Path path) {
        return new ArtifactCacheException(
                "Cached artifact at " + path + " is empty. Delete it and run the command again.");
    }

    private static void moveAtomically(Path source, Path path) {
        Path directory = path.getParent();
        try {
            Files.createDirectories(directory);
            Files.move(source, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new ArtifactCacheException(
                    "Could not write cached artifact at " + path + ". Check filesystem permissions.",
                    exception);
        }
    }

    private static CachedArtifact cached(
            Coordinate coordinate,
            String repositoryPath,
            Path cachePath,
            String source) {
        return new CachedArtifact(
                coordinate,
                repositoryPath,
                cachePath,
                size(cachePath),
                sha256(cachePath),
                source);
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new ArtifactCacheException("Could not inspect cached artifact at " + path + ".", exception);
        }
    }

    private static String sha256(Path path) {
        try (var input = Files.newInputStream(path)) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException exception) {
            throw new ArtifactCacheException("Could not hash cached artifact at " + path + ".", exception);
        }
    }
}
