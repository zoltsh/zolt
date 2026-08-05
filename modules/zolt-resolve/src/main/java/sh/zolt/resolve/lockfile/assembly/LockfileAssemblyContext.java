package sh.zolt.resolve.lockfile.assembly;

import sh.zolt.dependency.PackageId;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.materialization.MaterializedArtifact;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import java.util.List;
import java.util.Map;

public interface LockfileAssemblyContext {
    ProjectConfig config();

    /**
     * Materializes every selected artifact and describes it. Assembly needs each artifact's cache
     * path and digest, never its bytes, so a session may answer from what it already materialized for
     * another project rather than reading and hashing the file again.
     */
    Map<ArtifactDescriptor, MaterializedArtifact> getArtifacts(List<ArtifactDescriptor> descriptors);

    MaterializedArtifact getPomArtifact(Coordinate coordinate);

    String sourceFor(MaterializedArtifact artifact);

    Map<PackageId, ManagedVersion> projectManagedVersionDetails();

    void addLockfileAssemblyNanos(long nanos);
}
