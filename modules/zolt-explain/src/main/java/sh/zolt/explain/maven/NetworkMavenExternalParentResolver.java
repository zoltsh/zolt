package sh.zolt.explain.maven;

import sh.zolt.cache.CachedArtifact;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cache.RepositoryCacheScope;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.EffectiveRawPom;
import sh.zolt.maven.repository.MavenRepositoryClient;
import sh.zolt.maven.repository.PomInterpolationException;
import sh.zolt.maven.repository.RawPom;
import sh.zolt.maven.repository.RawPomDependency;
import sh.zolt.maven.repository.RawPomExclusion;
import sh.zolt.maven.repository.RawPomParser;
import sh.zolt.maven.repository.RepositoryArtifact;
import sh.zolt.maven.repository.RepositoryClientException;
import sh.zolt.maven.repository.RepositoryMissingArtifactException;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.metadata.pom.EffectivePomInheritanceBuilder;
import sh.zolt.resolve.metadata.pom.EffectivePomMetadataLoader;
import sh.zolt.resolve.metadata.pom.ImportedBomDependencyManagementExpander;
import sh.zolt.resolve.metadata.pom.ParentPomChainLoader;
import sh.zolt.resolve.metadata.pom.RawPomMetadataLoader;
import sh.zolt.resolve.traversal.GraphTraversalException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Recovers an external Maven parent chain over the network by reusing the resolver's POM loaders
 * ({@link ParentPomChainLoader}, {@link EffectivePomMetadataLoader}, and the import-BOM expander) on top
 * of the shared {@link MavenRepositoryClient} and the standard {@link LocalArtifactCache} at
 * {@code ~/.zolt/cache}, so re-runs stay offline-friendly.
 *
 * <p>Repositories are tried in order: the HTTPS repositories declared in the inspected POM chain first,
 * then Maven Central. A 404 or transport failure on one repository falls through to the next; if the
 * whole chain cannot be fetched, recovery returns {@link RecoveredParentMetadata#unresolved(String)} with
 * a review note rather than throwing.
 */
public final class NetworkMavenExternalParentResolver implements MavenExternalParentResolver {
    static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2";

    private final MavenRepositoryClient repositoryClient;
    private final LocalArtifactCache cache;
    private final String mavenCentralUrl;
    private final RawPomMetadataLoader rawPomMetadataLoader = new RawPomMetadataLoader(new RawPomParser());
    private final EffectivePomMetadataLoader effectivePomMetadataLoader = new EffectivePomMetadataLoader(
            new ParentPomChainLoader(),
            new EffectivePomInheritanceBuilder(),
            new ImportedBomDependencyManagementExpander());

    public NetworkMavenExternalParentResolver(
            MavenRepositoryClient repositoryClient, Path cacheRoot, String mavenCentralUrl) {
        this.repositoryClient = repositoryClient;
        this.cache = new LocalArtifactCache(cacheRoot);
        this.mavenCentralUrl = mavenCentralUrl;
    }

    /** Production wiring: the shared proxy/CA-aware client, the standard {@code ~/.zolt/cache}, and Central. */
    public static NetworkMavenExternalParentResolver usingSharedNetwork(MavenRepositoryClient client) {
        return new NetworkMavenExternalParentResolver(client, LocalArtifactCache.defaultRoot(), MAVEN_CENTRAL);
    }

    @Override
    public RecoveredParentMetadata resolve(Coordinate externalParent, List<String> repositoryUrls) {
        List<String> repositories = repositories(repositoryUrls);
        Provenance provenance = new Provenance();
        try {
            EffectiveRawPom effective = effectivePomMetadataLoader.load(
                    externalParent,
                    List.of(),
                    coordinate -> rawPom(coordinate, repositories, provenance),
                    NoopPomLoadMetrics.INSTANCE);
            return RecoveredParentMetadata.resolved(
                    effective.properties(),
                    managed(effective),
                    provenance.artifacts());
        } catch (RepositoryClientException
                | ResolveException
                | GraphTraversalException
                | PomInterpolationException exception) {
            return RecoveredParentMetadata.unresolved(fetchFailureNote(externalParent, repositories, exception));
        }
    }

    private List<String> repositories(List<String> declared) {
        List<String> repositories = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String url : declared) {
            if (url != null && !url.isBlank() && seen.add(url)) {
                repositories.add(url);
            }
        }
        if (seen.add(mavenCentralUrl)) {
            repositories.add(mavenCentralUrl);
        }
        return repositories;
    }

    private RawPom rawPom(Coordinate coordinate, List<String> repositories, Provenance provenance) {
        return rawPomMetadataLoader.load(
                coordinate,
                requested -> pom(requested, repositories, provenance),
                NoopPomLoadMetrics.INSTANCE);
    }

    private CachedArtifact pom(Coordinate coordinate, List<String> repositories, Provenance provenance) {
        RepositoryCacheScope scope = RepositoryCacheScope.of(String.join("\n", repositories));
        CachedArtifact artifact = cache.getOrFetchPom(
                scope,
                coordinate,
                requested -> fetchFromRepositories(requested, repositories));
        provenance.record(coordinate, artifact.source());
        return artifact;
    }

    private RepositoryArtifact fetchFromRepositories(
            Coordinate coordinate, List<String> repositories) {
        RepositoryClientException failure = null;
        for (String repository : repositories) {
            try {
                RepositoryArtifact artifact = repositoryClient.fetchPom(URI.create(repository), coordinate);
                return artifact.withRepositoryId(repository);
            } catch (RepositoryClientException exception) {
                failure = exception;
            }
        }
        throw failure != null
                ? failure
                : new RepositoryMissingArtifactException("No repository is configured to fetch " + coordinate + ".");
    }

    private static List<RecoveredManagedDependency> managed(EffectiveRawPom effective) {
        List<RecoveredManagedDependency> managed = new ArrayList<>();
        for (RawPomDependency dependency : effective.dependencyManagement()) {
            managed.add(new RecoveredManagedDependency(
                    dependency.groupId(),
                    dependency.artifactId(),
                    dependency.version().orElse(""),
                    dependency.scope().orElse(""),
                    dependency.type().orElse("jar"),
                    dependency.classifier().orElse(""),
                    exclusions(dependency.exclusions())));
        }
        return managed;
    }

    private static List<MavenDependencyExclusion> exclusions(List<RawPomExclusion> exclusions) {
        List<MavenDependencyExclusion> converted = new ArrayList<>();
        for (RawPomExclusion exclusion : exclusions) {
            converted.add(new MavenDependencyExclusion(exclusion.groupId(), exclusion.artifactId()));
        }
        return converted;
    }

    private static String fetchFailureNote(
            Coordinate coordinate, List<String> repositories, RuntimeException exception) {
        return "Could not recover external parent `" + coordinate + "` from " + repositories + ": "
                + exception.getMessage()
                + " Check repository or network access, or vendor the inherited Maven settings before"
                + " relying on the draft.";
    }

    /** Records the repository (or local cache) each recovered POM came from for the audit signal. */
    private static final class Provenance {
        private final Map<String, String> sources = new LinkedHashMap<>();

        void record(Coordinate coordinate, String repository) {
            sources.putIfAbsent(coordinate.toString(), repository);
        }

        List<RecoveredArtifact> artifacts() {
            List<RecoveredArtifact> artifacts = new ArrayList<>();
            sources.forEach((coordinate, source) -> artifacts.add(new RecoveredArtifact(coordinate, source)));
            return artifacts;
        }
    }
}
