package sh.zolt.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import sh.zolt.maven.Coordinate;

/** Validates local overlay files before recording content and scoped provenance. */
final class LocalOverlayCacheStorage {
    private final ScopedArtifactCacheStorage storage;

    LocalOverlayCacheStorage(ScopedArtifactCacheStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    CachedArtifact store(
            RepositoryCacheScope scope,
            Coordinate coordinate,
            String repositoryPath,
            String overlayId,
            Path sourcePath,
            String artifactKind) {
        String source = "local-overlay:" + validatedOverlayId(overlayId);
        if (!Files.isRegularFile(sourcePath)) {
            throw new ArtifactCacheException(
                    "Local repository overlay " + artifactKind + " for " + coordinate + " is missing at "
                            + sourcePath + ". Reinstall the artifact locally or remove it so Zolt can fall back to configured repositories.");
        }
        try {
            if (Files.size(sourcePath) == 0) {
                throw new ArtifactCacheException(
                        "Local repository overlay "
                                + artifactKind
                                + " for "
                                + coordinate
                                + " at "
                                + sourcePath
                                + " is empty. Reinstall the artifact locally or remove it so Zolt can fall back to configured repositories.");
            }
        } catch (IOException exception) {
            throw new ArtifactCacheException(
                    "Could not inspect local repository overlay at " + sourcePath + ".",
                    exception);
        }
        return storage.storeLocal(
                Objects.requireNonNull(scope, "scope"), coordinate, repositoryPath, source, sourcePath);
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
