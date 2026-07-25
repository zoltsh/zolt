package sh.zolt.resolve.traversal;

import sh.zolt.dependency.PackageId;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.EffectiveRawPom;
import sh.zolt.maven.repository.PomPropertyInterpolator;
import sh.zolt.maven.repository.RawPomRelocation;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.metadata.DependencyMetadataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DependencyRelocator {
    private static final int MAX_RELOCATION_DEPTH = 16;

    private final DependencyMetadataSource metadataSource;
    private final PomPropertyInterpolator interpolator = new PomPropertyInterpolator();
    private final Map<String, RelocationTarget> cache = new HashMap<>();

    public DependencyRelocator(DependencyMetadataSource metadataSource) {
        this.metadataSource = metadataSource;
    }

    public DependencyRequest relocate(DependencyRequest request) {
        return relocateWithPom(request).request();
    }

    RelocationResult relocateWithPom(DependencyRequest request) {
        DependencyRequest current = request;
        List<String> relocationStack = new ArrayList<>();
        for (int depth = 0; depth < MAX_RELOCATION_DEPTH; depth++) {
            Coordinate coordinate = coordinate(current);
            String key = coordinate.toString();
            RelocationTarget cached = cache.get(key);
            if (cached != null) {
                return new RelocationResult(requestWithTarget(current, cached), cached.pom());
            }
            if (relocationStack.contains(key)) {
                throw new GraphTraversalException(
                        "Dependency relocation cycle detected: "
                                + String.join(" -> ", relocationStack)
                                + " -> "
                                + key
                                + ". Replace the dependency with the final relocated coordinate.");
            }
            relocationStack.add(key);

            EffectiveRawPom pom = metadataSource.load(coordinate);
            Optional<RawPomRelocation> relocation = pom.rawPom().relocation();
            if (relocation.isEmpty()) {
                return cacheResult(relocationStack, current, pom);
            }

            DependencyRequest relocated = relocatedRequest(
                    current,
                    pom,
                    interpolator.interpolateRelocation(relocation.orElseThrow(), pom));
            if (coordinate(relocated).equals(coordinate)) {
                return cacheResult(relocationStack, current, pom);
            }
            current = relocated;
        }

        throw new GraphTraversalException(
                "Dependency relocation chain is too deep starting at "
                        + coordinate(request)
                        + ". Replace the dependency with the final relocated coordinate.");
    }

    private static DependencyRequest relocatedRequest(
            DependencyRequest request,
            EffectiveRawPom pom,
            RawPomRelocation relocation) {
        String groupId = relocation.groupId().orElse(pom.groupId());
        String artifactId = relocation.artifactId().orElse(pom.rawPom().artifactId());
        String version = relocation.version().orElse(pom.version());
        PackageId packageId = new PackageId(groupId, artifactId);
        return new DependencyRequest(
                packageId,
                version,
                request.scope(),
                request.origin(),
                relocatedArtifactDescriptor(request, packageId, version),
                request.exclusions(),
                request.optional());
    }

    private static Optional<ArtifactDescriptor> relocatedArtifactDescriptor(
            DependencyRequest request,
            PackageId packageId,
            String version) {
        return request.artifactDescriptor().map(descriptor -> new ArtifactDescriptor(
                new Coordinate(packageId.groupId(), packageId.artifactId(), Optional.of(version)),
                descriptor.classifier(),
                descriptor.extension()));
    }

    private static Coordinate coordinate(DependencyRequest request) {
        return new Coordinate(
                request.packageId().groupId(),
                request.packageId().artifactId(),
                Optional.of(request.requestedVersion()));
    }

    private RelocationResult cacheResult(
            List<String> relocationStack,
            DependencyRequest request,
            EffectiveRawPom pom) {
        RelocationTarget target = new RelocationTarget(request.packageId(), request.requestedVersion(), pom);
        for (String key : relocationStack) {
            cache.put(key, target);
        }
        return new RelocationResult(request, pom);
    }

    private static DependencyRequest requestWithTarget(DependencyRequest request, RelocationTarget target) {
        return new DependencyRequest(
                target.packageId(),
                target.version(),
                request.scope(),
                request.origin(),
                relocatedArtifactDescriptor(request, target.packageId(), target.version()),
                request.exclusions(),
                request.optional());
    }

    record RelocationResult(DependencyRequest request, EffectiveRawPom pom) {
    }

    private record RelocationTarget(PackageId packageId, String version, EffectiveRawPom pom) {
    }
}
