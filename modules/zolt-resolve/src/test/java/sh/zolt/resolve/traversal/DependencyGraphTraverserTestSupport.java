package sh.zolt.resolve.traversal;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.EffectiveRawPom;
import sh.zolt.maven.repository.RawPom;
import sh.zolt.maven.repository.RawPomDependency;
import sh.zolt.maven.repository.RawPomExclusion;
import sh.zolt.maven.repository.RawPomRelocation;
import sh.zolt.resolve.request.DependencyExclusion;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.metadata.DependencyMetadataSource;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

abstract class DependencyGraphTraverserTestSupport {
    final DependencyGraphTraverser traverser(MapBackedMetadataSource source) {
        return new DependencyGraphTraverser(source);
    }

    final DependencyGraphTraverser traverser(
            MapBackedMetadataSource source,
            Map<PackageId, ManagedVersion> rootManagedVersions) {
        return new DependencyGraphTraverser(
                source,
                sh.zolt.project.DependencyPolicySettings.defaults(),
                rootManagedVersions);
    }

    final DependencyRequest direct(String groupId, String artifactId, String version) {
        return new DependencyRequest(new PackageId(groupId, artifactId), version, DependencyScope.COMPILE, RequestOrigin.DIRECT);
    }

    final DependencyRequest optionalDirect(
            String groupId,
            String artifactId,
            String version) {
        return new DependencyRequest(
                new PackageId(groupId, artifactId),
                version,
                DependencyScope.COMPILE,
                RequestOrigin.DIRECT,
                Optional.empty(),
                List.of(),
                true);
    }

    final DependencyRequest directWithExclusion(
            String groupId,
            String artifactId,
            String version,
            String excludedGroupId,
            String excludedArtifactId) {
        return new DependencyRequest(
                new PackageId(groupId, artifactId),
                version,
                DependencyScope.COMPILE,
                RequestOrigin.DIRECT,
                List.of(new DependencyExclusion(excludedGroupId, excludedArtifactId)));
    }

    final DependencyRequest directTest(String groupId, String artifactId, String version) {
        return new DependencyRequest(new PackageId(groupId, artifactId), version, DependencyScope.TEST, RequestOrigin.DIRECT);
    }

    final EffectiveRawPom pom(
            String groupId,
            String artifactId,
            String version,
            List<RawPomDependency> dependencies) {
        RawPom rawPom = new RawPom(
                Optional.of(groupId),
                artifactId,
                Optional.of(version),
                "jar",
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                List.of(),
                dependencies);
        return new EffectiveRawPom(rawPom, List.of(), groupId, version, Map.of(), List.of());
    }

    final EffectiveRawPom relocatedPom(
            String groupId,
            String artifactId,
            String version,
            String relocatedGroupId,
            String relocatedArtifactId,
            String relocatedVersion) {
        RawPom rawPom = new RawPom(
                Optional.of(groupId),
                artifactId,
                Optional.of(version),
                "pom",
                Optional.empty(),
                Optional.of(new RawPomRelocation(
                        Optional.of(relocatedGroupId),
                        Optional.of(relocatedArtifactId),
                        Optional.of(relocatedVersion),
                        Optional.empty())),
                Map.of(),
                List.of(),
                List.of());
        return new EffectiveRawPom(rawPom, List.of(), groupId, version, Map.of(), List.of());
    }

    final RawPomDependency dependency(String groupId, String artifactId, String version) {
        return new RawPomDependency(groupId, artifactId, Optional.of(version), Optional.empty(), Optional.empty(), Optional.empty(), false, List.of());
    }

    final RawPomDependency runtimeDependency(String groupId, String artifactId, String version) {
        return new RawPomDependency(groupId, artifactId, Optional.of(version), Optional.of("runtime"), Optional.empty(), Optional.empty(), false, List.of());
    }

    final RawPomDependency testDependency(String groupId, String artifactId, String version) {
        return new RawPomDependency(groupId, artifactId, Optional.of(version), Optional.of("test"), Optional.empty(), Optional.empty(), false, List.of());
    }

    final RawPomDependency providedDependency(String groupId, String artifactId, String version) {
        return new RawPomDependency(groupId, artifactId, Optional.of(version), Optional.of("provided"), Optional.empty(), Optional.empty(), false, List.of());
    }

    final RawPomDependency optionalDependency(String groupId, String artifactId, String version) {
        return new RawPomDependency(groupId, artifactId, Optional.of(version), Optional.empty(), Optional.empty(), Optional.empty(), true, List.of());
    }

    final RawPomDependency versionlessDependency(String groupId, String artifactId) {
        return new RawPomDependency(groupId, artifactId, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, List.of());
    }

    final RawPomDependency dependencyWithExclusion(
            String groupId,
            String artifactId,
            String version,
            String excludedGroupId,
            String excludedArtifactId) {
        return new RawPomDependency(
                groupId,
                artifactId,
                Optional.of(version),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                List.of(new RawPomExclusion(excludedGroupId, excludedArtifactId)));
    }

    final List<String> nodeStrings(ResolutionGraph graph) {
        return graph.nodes().stream()
                .map(node -> node.packageId() + ":" + node.selectedVersion())
                .toList();
    }

    final List<String> edgeStrings(ResolutionGraph graph) {
        return graph.edges().stream()
                .map(edge -> edge.from().packageId()
                        + ":"
                        + edge.from().selectedVersion()
                        + "->"
                        + edge.to().packageId()
                        + ":"
                        + edge.to().selectedVersion())
                .toList();
    }
}

final class MapBackedMetadataSource implements DependencyMetadataSource {
    private final Map<String, EffectiveRawPom> poms = new HashMap<>();
    private final Map<String, Integer> loadCounts = new HashMap<>();

    void put(String coordinate, EffectiveRawPom pom) {
        poms.put(coordinate, pom);
    }

    int loadCount(String coordinate) {
        return loadCounts.getOrDefault(coordinate, 0);
    }

    @Override
    public EffectiveRawPom load(Coordinate coordinate) {
        String key = coordinate.toString();
        loadCounts.merge(key, 1, Integer::sum);
        EffectiveRawPom pom = poms.get(key);
        if (pom == null) {
            throw new GraphTraversalException("No test POM for " + key);
        }
        return pom;
    }
}
