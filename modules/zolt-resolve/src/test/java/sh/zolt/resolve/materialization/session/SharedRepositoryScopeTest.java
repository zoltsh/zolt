package sh.zolt.resolve.materialization.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cache.CachedArtifact;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.RawPomParser;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.materialization.MaterializedArtifact;
import sh.zolt.resolve.metrics.ResolverMetricsCollector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the shared session may answer from memory, and what it must still go and do. */
final class SharedRepositoryScopeTest {
    private final SharedRepositoryScope scope = new SharedRepositoryScope(new RawPomParser());
    private final ResolverMetricsCollector metrics = new ResolverMetricsCollector();

    @TempDir
    private Path tempDir;

    @Test
    void materializesOnlyTheArtifactsItHasNotDescribedBefore() {
        ArtifactDescriptor alpha = jar("alpha");
        ArtifactDescriptor beta = jar("beta");
        List<List<ArtifactDescriptor>> batches = new ArrayList<>();

        Map<ArtifactDescriptor, MaterializedArtifact> first =
                scope.materializedArtifacts(List.of(alpha), recording(batches), metrics);
        Map<ArtifactDescriptor, MaterializedArtifact> second =
                scope.materializedArtifacts(List.of(alpha, beta), recording(batches), metrics);

        assertEquals(List.of(List.of(alpha), List.of(beta)), batches);
        assertEquals(first.get(alpha), second.get(alpha));
        assertEquals(described(beta), second.get(beta));
    }

    @Test
    void skipsTheBatchEntirelyWhenEveryArtifactIsAlreadyDescribed() {
        ArtifactDescriptor alpha = jar("alpha");
        scope.materializedArtifacts(List.of(alpha), this::describeAll, metrics);

        Map<ArtifactDescriptor, MaterializedArtifact> again = scope.materializedArtifacts(
                List.of(alpha, alpha),
                descriptors -> {
                    throw new AssertionError("materialized an artifact this scope already described");
                },
                metrics);

        assertEquals(Map.of(alpha, described(alpha)), again);
        assertTrue(metrics.metrics().artifactCacheHits() >= 1);
    }

    /**
     * One cache-relative path is one file for the life of a command, so its digest is computed once —
     * the second describe of the same path is answered without hashing again.
     */
    @Test
    void digestsOneCacheRelativePathOnce() {
        WorkspaceResolutionSession session = new WorkspaceResolutionSession(
                tempDir, ResolveOptions.defaults(), new RawPomParser());

        MaterializedArtifact first = session.describe(cachedArtifact("a/b/c.jar", "first"));
        MaterializedArtifact second = session.describe(cachedArtifact("a/b/c.jar", "second"));

        assertEquals(first, second);
    }

    private java.util.function.Function<List<ArtifactDescriptor>, Map<ArtifactDescriptor, MaterializedArtifact>>
            recording(List<List<ArtifactDescriptor>> batches) {
        return descriptors -> {
            batches.add(List.copyOf(descriptors));
            return describeAll(descriptors);
        };
    }

    private Map<ArtifactDescriptor, MaterializedArtifact> describeAll(List<ArtifactDescriptor> descriptors) {
        Map<ArtifactDescriptor, MaterializedArtifact> artifacts = new LinkedHashMap<>();
        descriptors.forEach(descriptor -> artifacts.put(descriptor, described(descriptor)));
        return artifacts;
    }

    private static MaterializedArtifact described(ArtifactDescriptor descriptor) {
        return new MaterializedArtifact(
                descriptor.coordinate().artifactId() + ".jar",
                "sha256-of-" + descriptor.coordinate().artifactId());
    }

    private static ArtifactDescriptor jar(String artifactId) {
        return ArtifactDescriptor.jar(new Coordinate("com.example", artifactId, Optional.of("1.0.0")));
    }

    private static CachedArtifact cachedArtifact(String repositoryPath, String content) {
        return new CachedArtifact(
                new Coordinate("com.example", "artifact", Optional.of("1.0.0")),
                repositoryPath,
                Path.of(repositoryPath),
                content.getBytes(StandardCharsets.UTF_8));
    }
}
