package sh.zolt.resolve;

import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.resolve.request.DependencyRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class DependencyRequestVersions {
    private DependencyRequestVersions() {
    }

    static List<DependencyRequest> rewrite(
            List<DependencyRequest> requests,
            Map<ResolutionVariant, String> versions) {
        return requests.stream()
                .map(request -> rewrite(request, versions))
                .toList();
    }

    static DependencyRequest rewrite(
            DependencyRequest request,
            Map<ResolutionVariant, String> versions) {
        String version = versions.get(new ResolutionVariant(
                request.packageId(), request.artifactVariant()));
        if (version == null || version.equals(request.requestedVersion())) {
            return request;
        }
        Optional<ArtifactDescriptor> descriptor = request.artifactDescriptor()
                .map(value -> new ArtifactDescriptor(
                        new Coordinate(
                                request.packageId().groupId(),
                                request.packageId().artifactId(),
                                Optional.of(version)),
                        value.classifier(),
                        value.extension()));
        return new DependencyRequest(
                request.packageId(),
                version,
                request.scope(),
                request.origin(),
                descriptor,
                request.exclusions());
    }
}
