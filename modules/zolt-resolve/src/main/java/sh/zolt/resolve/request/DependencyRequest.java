package sh.zolt.resolve.request;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.lockfile.LockArtifactVariant;
import java.util.List;
import java.util.Optional;

public record DependencyRequest(
        PackageId packageId,
        String requestedVersion,
        DependencyScope scope,
        RequestOrigin origin,
        Optional<ArtifactDescriptor> artifactDescriptor,
        List<DependencyExclusion> exclusions,
        boolean optional,
        RequestVersionOrigin versionOrigin) {
    public DependencyRequest(
            PackageId packageId,
            String requestedVersion,
            DependencyScope scope,
            RequestOrigin origin,
            Optional<ArtifactDescriptor> artifactDescriptor,
            List<DependencyExclusion> exclusions,
            boolean optional) {
        this(
                packageId,
                requestedVersion,
                scope,
                origin,
                artifactDescriptor,
                exclusions,
                optional,
                RequestVersionOrigin.DECLARED);
    }

    public DependencyRequest(
            PackageId packageId,
            String requestedVersion,
            DependencyScope scope,
            RequestOrigin origin,
            Optional<ArtifactDescriptor> artifactDescriptor,
            List<DependencyExclusion> exclusions) {
        this(
                packageId,
                requestedVersion,
                scope,
                origin,
                artifactDescriptor,
                exclusions,
                false);
    }

    public DependencyRequest(
            PackageId packageId,
            String requestedVersion,
            DependencyScope scope,
            RequestOrigin origin,
            Optional<ArtifactDescriptor> artifactDescriptor) {
        this(packageId, requestedVersion, scope, origin, artifactDescriptor, List.of());
    }

    public DependencyRequest(
            PackageId packageId,
            String requestedVersion,
            DependencyScope scope,
            RequestOrigin origin) {
        this(packageId, requestedVersion, scope, origin, Optional.empty(), List.of());
    }

    public DependencyRequest(
            PackageId packageId,
            String requestedVersion,
            DependencyScope scope,
            RequestOrigin origin,
            RequestVersionOrigin versionOrigin) {
        this(
                packageId,
                requestedVersion,
                scope,
                origin,
                Optional.empty(),
                List.of(),
                false,
                versionOrigin);
    }

    public DependencyRequest(
            PackageId packageId,
            String requestedVersion,
            DependencyScope scope,
            RequestOrigin origin,
            List<DependencyExclusion> exclusions) {
        this(packageId, requestedVersion, scope, origin, Optional.empty(), exclusions);
    }

    public DependencyRequest {
        artifactDescriptor = artifactDescriptor == null ? Optional.empty() : artifactDescriptor;
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        versionOrigin = versionOrigin == null ? RequestVersionOrigin.DECLARED : versionOrigin;
    }

    public boolean direct() {
        return origin == RequestOrigin.DIRECT;
    }

    /** The mediation lane this request belongs to. */
    public LockArtifactVariant artifactVariant() {
        return LockArtifactVariant.of(artifactDescriptor);
    }
}
