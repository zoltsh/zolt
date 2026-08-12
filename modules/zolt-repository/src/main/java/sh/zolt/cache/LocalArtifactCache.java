package sh.zolt.cache;

import sh.zolt.concurrent.RepositoryExecutionLane;
import sh.zolt.home.UserGlobalDirectory;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.MavenRepositoryPathBuilder;
import sh.zolt.maven.repository.RepositoryArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public final class LocalArtifactCache {
    private final Path root;
    private final MavenRepositoryPathBuilder pathBuilder;
    private final DownloadCoordinator downloadCoordinator;

    public LocalArtifactCache(Path root) {
        this(root, new MavenRepositoryPathBuilder(), new DownloadCoordinator());
    }

    public LocalArtifactCache(Path root, DownloadCoordinator downloadCoordinator) {
        this(root, new MavenRepositoryPathBuilder(), downloadCoordinator);
    }

    LocalArtifactCache(Path root, MavenRepositoryPathBuilder pathBuilder, DownloadCoordinator downloadCoordinator) {
        this.root = Objects.requireNonNull(root, "root");
        this.pathBuilder = Objects.requireNonNull(pathBuilder, "pathBuilder");
        this.downloadCoordinator = Objects.requireNonNull(downloadCoordinator, "downloadCoordinator");
    }

    public static Path defaultRoot() {
        return UserGlobalDirectory.artifactCache();
    }

    public int downloadConcurrency() {
        return downloadCoordinator.concurrency();
    }

    public RepositoryExecutionLane repositoryExecutionLane() {
        return downloadCoordinator.executionLane();
    }

    public Path pomPath(Coordinate coordinate) {
        return cachePath(pathBuilder.pomPath(coordinate));
    }

    public Path jarPath(Coordinate coordinate) {
        return cachePath(pathBuilder.jarPath(coordinate));
    }

    public Path artifactPath(ArtifactDescriptor descriptor) {
        return cachePath(pathBuilder.artifactPath(descriptor));
    }

    public CachedArtifact getOrFetchPom(Coordinate coordinate, ArtifactFetcher fetcher) {
        return getOrFetch(coordinate, pathBuilder.pomPath(coordinate), fetcher);
    }

    public CachedArtifact getOrFetchJar(Coordinate coordinate, ArtifactFetcher fetcher) {
        return getOrFetch(coordinate, pathBuilder.jarPath(coordinate), fetcher);
    }

    public CachedArtifact getOrFetchArtifact(ArtifactDescriptor descriptor, ArtifactFetcher fetcher) {
        return getOrFetch(descriptor.coordinate(), pathBuilder.artifactPath(descriptor), fetcher);
    }

    public CachedArtifact materializeOverlayPom(Coordinate coordinate, String overlayId, Path sourcePath) {
        return materializeOverlayArtifact(coordinate, overlayPath(overlayId, pathBuilder.pomPath(coordinate)), sourcePath, "POM");
    }

    public CachedArtifact materializeOverlayArtifact(
            ArtifactDescriptor descriptor,
            String overlayId,
            Path sourcePath) {
        return materializeOverlayArtifact(
                descriptor.coordinate(),
                overlayPath(overlayId, pathBuilder.artifactPath(descriptor)),
                sourcePath,
                descriptor.extension().toUpperCase(java.util.Locale.ROOT));
    }

    public CachedArtifact getCachedPom(Coordinate coordinate) {
        return getCached(coordinate, pathBuilder.pomPath(coordinate), "POM");
    }

    public CachedArtifact getCachedJar(Coordinate coordinate) {
        return getCached(coordinate, pathBuilder.jarPath(coordinate), "JAR");
    }

    public CachedArtifact getCachedArtifact(ArtifactDescriptor descriptor, String artifactKind) {
        return getCached(descriptor.coordinate(), pathBuilder.artifactPath(descriptor), artifactKind);
    }

    private CachedArtifact getOrFetch(Coordinate coordinate, String repositoryPath, ArtifactFetcher fetcher) {
        Path cachePath = cachePath(repositoryPath);
        if (Files.isRegularFile(cachePath)) {
            byte[] bytes = read(cachePath);
            if (bytes.length > 0) {
                return new CachedArtifact(coordinate, repositoryPath, cachePath, bytes);
            }
            throw new ArtifactCacheException(
                    "Cached artifact at " + cachePath + " is empty. Delete it and run the command again.");
        }

        return downloadCoordinator.run(repositoryPath, () -> {
            RepositoryArtifact artifact = fetcher.fetch(coordinate);
            if (artifact.bytes().length == 0) {
                throw new ArtifactCacheException(
                        "Downloaded artifact " + coordinate + " is empty. The cache was not updated.");
            }
            writeAtomically(cachePath, artifact.bytes());
            return new CachedArtifact(coordinate, repositoryPath, cachePath, artifact.bytes());
        });
    }

    private CachedArtifact getCached(Coordinate coordinate, String repositoryPath, String artifactKind) {
        Path cachePath = cachePath(repositoryPath);
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
            throw new ArtifactCacheException(
                    "Cached artifact at " + cachePath + " is empty. Delete it and run the command again.");
        }
        return new CachedArtifact(coordinate, repositoryPath, cachePath, bytes);
    }

    private CachedArtifact materializeOverlayArtifact(
            Coordinate coordinate,
            String repositoryPath,
            Path sourcePath,
            String artifactKind) {
        byte[] bytes = read(sourcePath);
        if (bytes.length == 0) {
            throw new ArtifactCacheException(
                    "Local repository overlay "
                            + artifactKind
                            + " for "
                            + coordinate
                            + " at "
                            + sourcePath
                            + " is empty. Reinstall the artifact locally or remove it so Zolt can fall back to configured repositories.");
        }
        Path cachePath = cachePath(repositoryPath);
        writeAtomically(cachePath, bytes);
        return new CachedArtifact(coordinate, repositoryPath, cachePath, bytes);
    }

    private Path cachePath(String repositoryPath) {
        Path cacheRoot = root.toAbsolutePath().normalize();
        Path resolved = root.resolve(repositoryPath).normalize();
        Path absoluteResolved = resolved.toAbsolutePath().normalize();
        if (!absoluteResolved.startsWith(cacheRoot) || absoluteResolved.equals(cacheRoot)) {
            throw new ArtifactCacheException("Refusing artifact cache path outside the configured cache root.");
        }
        return resolved;
    }

    private static String overlayPath(String overlayId, String repositoryPath) {
        return "overlays/" + overlayId + "/" + repositoryPath;
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
