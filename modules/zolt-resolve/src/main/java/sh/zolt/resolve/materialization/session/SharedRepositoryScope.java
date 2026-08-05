package sh.zolt.resolve.materialization.session;

import sh.zolt.cache.CachedArtifact;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.EffectiveRawPom;
import sh.zolt.maven.repository.RawPom;
import sh.zolt.maven.repository.RawPomParser;
import sh.zolt.resolve.materialization.MaterializedArtifact;
import sh.zolt.resolve.metadata.pom.EffectivePomInheritanceBuilder;
import sh.zolt.resolve.metadata.pom.EffectivePomMetadataLoader;
import sh.zolt.resolve.metadata.pom.ImportedBomDependencyManagementExpander;
import sh.zolt.resolve.metadata.pom.ParentPomChainLoader;
import sh.zolt.resolve.metadata.pom.RawPomMetadataLoader;
import sh.zolt.resolve.metrics.ArtifactLoadMetricsSink;
import sh.zolt.resolve.metrics.EffectivePomLoadMetricsSink;
import sh.zolt.resolve.metrics.RawPomLoadMetricsSink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The metadata every project sharing one repository configuration derives identically.
 *
 * <p>A raw POM, the parent chain above it, the imported BOMs it expands to, and the POM artifact
 * itself are functions of the coordinate and the bytes the repositories serve for it — never of the
 * requesting project's dependencies, platforms, or policy. Two projects whose repository
 * configuration identity matches therefore derive the same values, which is why one scope may serve
 * them both; two whose identity differs get separate scopes, so a value can never cross a repository
 * boundary that could have served different bytes.
 *
 * <p>Metrics stay with the caller: every entry point takes the requesting session's sink, so a
 * project that reads a value another project derived records it as the cache hit it is.
 */
final class SharedRepositoryScope {
    private final RawPomMetadataLoader rawPomMetadataLoader;
    private final EffectivePomMetadataLoader effectivePomMetadataLoader;
    private final Map<String, CachedArtifact> pomArtifacts = new ConcurrentHashMap<>();
    private final Map<ArtifactDescriptor, MaterializedArtifact> selectedArtifacts = new ConcurrentHashMap<>();

    SharedRepositoryScope(RawPomParser rawPomParser) {
        this.rawPomMetadataLoader = new RawPomMetadataLoader(rawPomParser);
        this.effectivePomMetadataLoader = new EffectivePomMetadataLoader(
                new ParentPomChainLoader(),
                new EffectivePomInheritanceBuilder(),
                new ImportedBomDependencyManagementExpander());
    }

    EffectiveRawPom effectivePom(
            Coordinate coordinate,
            Function<Coordinate, RawPom> rawPomLoader,
            EffectivePomLoadMetricsSink metrics) {
        return effectivePomMetadataLoader.load(coordinate, List.of(), rawPomLoader, metrics);
    }

    RawPom rawPom(
            Coordinate coordinate,
            Function<Coordinate, CachedArtifact> pomArtifactLoader,
            RawPomLoadMetricsSink metrics) {
        return rawPomMetadataLoader.load(coordinate, pomArtifactLoader, metrics);
    }

    /**
     * The POM artifact for {@code coordinate}, materialized once. Lockfile assembly asks for it again
     * after traversal already parsed it, so without this the same file is read and copied out of the
     * cache once per project that selected the package. A reused artifact is still a cache hit, and is
     * recorded as one, because that is exactly what it was the first time.
     */
    CachedArtifact pomArtifact(
            Coordinate coordinate,
            Function<Coordinate, CachedArtifact> materializer,
            ArtifactLoadMetricsSink metrics) {
        long started = System.nanoTime();
        CachedArtifact cached = pomArtifacts.get(coordinate.toString());
        if (cached != null) {
            metrics.recordPomCacheHit(Math.max(0L, System.nanoTime() - started));
            return cached;
        }
        CachedArtifact materialized = materializer.apply(coordinate);
        pomArtifacts.putIfAbsent(coordinate.toString(), materialized);
        return materialized;
    }

    /**
     * Describes every selected artifact, materializing only the ones this scope has not described
     * before. An artifact many projects select is read and hashed once; the projects that follow get
     * the recorded answer, which is the cache hit re-reading the file used to be. When nothing is
     * missing the batch is skipped entirely rather than opened to do nothing.
     */
    Map<ArtifactDescriptor, MaterializedArtifact> materializedArtifacts(
            List<ArtifactDescriptor> descriptors,
            Function<List<ArtifactDescriptor>, Map<ArtifactDescriptor, MaterializedArtifact>> materializer,
            ArtifactLoadMetricsSink metrics) {
        List<ArtifactDescriptor> requested = List.copyOf(new LinkedHashSet<>(descriptors));
        List<ArtifactDescriptor> missing = new ArrayList<>();
        for (ArtifactDescriptor descriptor : requested) {
            long started = System.nanoTime();
            if (selectedArtifacts.containsKey(descriptor)) {
                metrics.recordArtifactCacheHit(Math.max(0L, System.nanoTime() - started));
            } else {
                missing.add(descriptor);
            }
        }
        if (!missing.isEmpty()) {
            selectedArtifacts.putAll(materializer.apply(List.copyOf(missing)));
        }
        Map<ArtifactDescriptor, MaterializedArtifact> artifacts = new LinkedHashMap<>();
        for (ArtifactDescriptor descriptor : requested) {
            artifacts.put(descriptor, selectedArtifacts.get(descriptor));
        }
        return Map.copyOf(artifacts);
    }
}
