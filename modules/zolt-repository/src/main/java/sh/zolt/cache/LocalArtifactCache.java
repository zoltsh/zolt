package sh.zolt.cache;

import sh.zolt.concurrent.RepositoryExecutionLane;
import sh.zolt.home.UserGlobalDirectory;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.MavenRepositoryPathBuilder;
import java.nio.file.Path;
import java.util.Objects;

public final class LocalArtifactCache {
    private final MavenRepositoryPathBuilder pathBuilder;
    private final DownloadCoordinator downloadCoordinator;
    private final LegacyArtifactCacheStorage legacyStorage;
    private final ScopedArtifactCacheStorage scopedStorage;

    public LocalArtifactCache(Path root) {
        this(root, new MavenRepositoryPathBuilder(), new DownloadCoordinator());
    }

    public LocalArtifactCache(Path root, DownloadCoordinator downloadCoordinator) {
        this(root, new MavenRepositoryPathBuilder(), downloadCoordinator);
    }

    LocalArtifactCache(Path root, MavenRepositoryPathBuilder pathBuilder, DownloadCoordinator downloadCoordinator) {
        Objects.requireNonNull(root, "root");
        this.pathBuilder = Objects.requireNonNull(pathBuilder, "pathBuilder");
        this.downloadCoordinator = Objects.requireNonNull(downloadCoordinator, "downloadCoordinator");
        this.legacyStorage = new LegacyArtifactCacheStorage(root, downloadCoordinator);
        this.scopedStorage = new ScopedArtifactCacheStorage(root);
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
        return legacyStorage.path(pathBuilder.pomPath(coordinate));
    }

    public Path jarPath(Coordinate coordinate) {
        return legacyStorage.path(pathBuilder.jarPath(coordinate));
    }

    public Path artifactPath(ArtifactDescriptor descriptor) {
        return legacyStorage.path(pathBuilder.artifactPath(descriptor));
    }

    public CachedArtifact getOrFetchPom(Coordinate coordinate, ArtifactFetcher fetcher) {
        return legacyStorage.getOrFetch(coordinate, pathBuilder.pomPath(coordinate), fetcher);
    }

    public CachedArtifact getOrFetchJar(Coordinate coordinate, ArtifactFetcher fetcher) {
        return legacyStorage.getOrFetch(coordinate, pathBuilder.jarPath(coordinate), fetcher);
    }

    public CachedArtifact getOrFetchArtifact(ArtifactDescriptor descriptor, ArtifactFetcher fetcher) {
        return legacyStorage.getOrFetch(
                descriptor.coordinate(), pathBuilder.artifactPath(descriptor), fetcher);
    }

    public boolean hasPom(RepositoryCacheScope scope, Coordinate coordinate) {
        return scopedStorage.contains(scope, pathBuilder.pomPath(coordinate));
    }

    public boolean hasJar(RepositoryCacheScope scope, Coordinate coordinate) {
        return scopedStorage.contains(scope, pathBuilder.jarPath(coordinate));
    }

    public boolean hasArtifact(RepositoryCacheScope scope, ArtifactDescriptor descriptor) {
        return scopedStorage.contains(scope, pathBuilder.artifactPath(descriptor));
    }

    public CachedArtifact getOrFetchPom(
            RepositoryCacheScope scope,
            Coordinate coordinate,
            ArtifactFetcher fetcher) {
        return getOrFetch(scope, coordinate, pathBuilder.pomPath(coordinate), fetcher);
    }

    public CachedArtifact getOrFetchJar(
            RepositoryCacheScope scope,
            Coordinate coordinate,
            ArtifactFetcher fetcher) {
        return getOrFetch(scope, coordinate, pathBuilder.jarPath(coordinate), fetcher);
    }

    public CachedArtifact getOrFetchArtifact(
            RepositoryCacheScope scope,
            ArtifactDescriptor descriptor,
            ArtifactFetcher fetcher) {
        return getOrFetch(scope, descriptor.coordinate(), pathBuilder.artifactPath(descriptor), fetcher);
    }

    public CachedArtifact materializeOverlayPom(Coordinate coordinate, String overlayId, Path sourcePath) {
        return materializeOverlayArtifact(
                coordinate,
                pathBuilder.pomPath(coordinate),
                validatedOverlayId(overlayId),
                sourcePath,
                "POM");
    }

    public CachedArtifact materializeOverlayArtifact(
            ArtifactDescriptor descriptor,
            String overlayId,
            Path sourcePath) {
        return materializeOverlayArtifact(
                descriptor.coordinate(),
                pathBuilder.artifactPath(descriptor),
                validatedOverlayId(overlayId),
                sourcePath,
                descriptor.extension().toUpperCase(java.util.Locale.ROOT));
    }

    public CachedArtifact getCachedPom(Coordinate coordinate) {
        return legacyStorage.getCached(coordinate, pathBuilder.pomPath(coordinate), "POM");
    }

    public CachedArtifact getCachedJar(Coordinate coordinate) {
        return legacyStorage.getCached(coordinate, pathBuilder.jarPath(coordinate), "JAR");
    }

    public CachedArtifact getCachedArtifact(ArtifactDescriptor descriptor, String artifactKind) {
        return legacyStorage.getCached(
                descriptor.coordinate(), pathBuilder.artifactPath(descriptor), artifactKind);
    }

    public CachedArtifact getCachedPom(RepositoryCacheScope scope, Coordinate coordinate) {
        return getCached(scope, coordinate, pathBuilder.pomPath(coordinate), "POM");
    }

    public CachedArtifact getCachedJar(RepositoryCacheScope scope, Coordinate coordinate) {
        return getCached(scope, coordinate, pathBuilder.jarPath(coordinate), "JAR");
    }

    public CachedArtifact getCachedArtifact(
            RepositoryCacheScope scope,
            ArtifactDescriptor descriptor,
            String artifactKind) {
        return getCached(scope, descriptor.coordinate(), pathBuilder.artifactPath(descriptor), artifactKind);
    }

    private CachedArtifact getOrFetch(
            RepositoryCacheScope scope,
            Coordinate coordinate,
            String repositoryPath,
            ArtifactFetcher fetcher) {
        CachedArtifact cached = scopedStorage.find(scope, coordinate, repositoryPath).orElse(null);
        if (cached != null) {
            return cached;
        }
        String inFlightKey = scope.key() + ":" + repositoryPath;
        return downloadCoordinator.run(inFlightKey, () -> {
            CachedArtifact concurrent = scopedStorage.find(scope, coordinate, repositoryPath).orElse(null);
            if (concurrent != null) {
                return concurrent;
            }
            return scopedStorage.store(scope, coordinate, repositoryPath, fetcher.fetch(coordinate));
        });
    }

    private CachedArtifact getCached(
            RepositoryCacheScope scope,
            Coordinate coordinate,
            String repositoryPath,
            String artifactKind) {
        return scopedStorage.find(scope, coordinate, repositoryPath).orElseThrow(() ->
                new ArtifactCacheException(
                        "Offline mode requires cached "
                                + artifactKind
                                + " for "
                                + coordinate
                                + " at repository path "
                                + repositoryPath
                                + " in repository scope "
                                + scope.key()
                                + ". Run the command without --offline to download it, then retry with --offline."));
    }

    private CachedArtifact materializeOverlayArtifact(
            Coordinate coordinate,
            String repositoryPath,
            String overlayId,
            Path sourcePath,
            String artifactKind) {
        byte[] bytes = LegacyArtifactCacheStorage.read(sourcePath);
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
        return scopedStorage.storeLocal(
                coordinate,
                repositoryPath,
                "local-overlay:" + overlayId,
                bytes);
    }

    private static String validatedOverlayId(String overlayId) {
        if (overlayId == null
                || !overlayId.matches("[A-Za-z0-9._-]+")
                || overlayId.equals(".")
                || overlayId.equals("..")) {
            throw new ArtifactCacheException("Refusing invalid local repository overlay id.");
        }
        return overlayId;
    }
}
