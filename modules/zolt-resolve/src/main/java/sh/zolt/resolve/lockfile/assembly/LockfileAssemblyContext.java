package sh.zolt.resolve.lockfile.assembly;

import sh.zolt.cache.CachedArtifact;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import java.util.List;
import java.util.Map;

public interface LockfileAssemblyContext {
    ProjectConfig config();

    Map<ArtifactDescriptor, CachedArtifact> getArtifacts(List<ArtifactDescriptor> descriptors);

    CachedArtifact getPom(Coordinate coordinate);

    String sourceFor(CachedArtifact artifact);

    /**
     * The SHA-256 the lock records for {@code artifact}. The session answers it, so an artifact many
     * projects select is hashed once instead of once per project.
     */
    String digest(CachedArtifact artifact);

    Map<PackageId, ManagedVersion> projectManagedVersionDetails();

    void addLockfileAssemblyNanos(long nanos);
}
