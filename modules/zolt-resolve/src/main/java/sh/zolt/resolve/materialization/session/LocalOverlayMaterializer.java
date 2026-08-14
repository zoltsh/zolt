package sh.zolt.resolve.materialization.session;

import sh.zolt.cache.CachedArtifact;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cache.RepositoryCacheScope;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.MavenRepositoryPathBuilder;
import sh.zolt.resolve.materialization.RepositoryOverlay;
import sh.zolt.resolve.materialization.RepositoryOverlayKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class LocalOverlayMaterializer {
    private final LocalArtifactCache cache;
    private final MavenRepositoryPathBuilder repositoryPathBuilder = new MavenRepositoryPathBuilder();

    LocalOverlayMaterializer(LocalArtifactCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    Optional<CachedArtifact> materializePom(List<RepositoryOverlay> overlays, Coordinate coordinate) {
        for (RepositoryOverlay overlay : overlays) {
            if (overlay.kind() != RepositoryOverlayKind.MAVEN_LOCAL) {
                continue;
            }
            Path sourcePath = overlay.root().resolve(repositoryPathBuilder.pomPath(coordinate)).normalize();
            if (!Files.isRegularFile(sourcePath)) {
                continue;
            }
            CachedArtifact artifact = cache.materializeOverlayPom(
                    scope(overlay), coordinate, overlay.id(), sourcePath);
            return Optional.of(artifact);
        }
        return Optional.empty();
    }

    Optional<CachedArtifact> materializeArtifact(List<RepositoryOverlay> overlays, ArtifactDescriptor descriptor) {
        for (RepositoryOverlay overlay : overlays) {
            if (overlay.kind() != RepositoryOverlayKind.MAVEN_LOCAL) {
                continue;
            }
            Path sourcePath = overlay.root().resolve(repositoryPathBuilder.artifactPath(descriptor)).normalize();
            if (!Files.isRegularFile(sourcePath)) {
                continue;
            }
            CachedArtifact artifact = cache.materializeOverlayArtifact(
                    scope(overlay), descriptor, overlay.id(), sourcePath);
            return Optional.of(artifact);
        }
        return Optional.empty();
    }

    private static RepositoryCacheScope scope(RepositoryOverlay overlay) {
        return RepositoryCacheScope.forOverlay(
                overlay.kind().name(), overlay.id(), overlay.root());
    }
}
