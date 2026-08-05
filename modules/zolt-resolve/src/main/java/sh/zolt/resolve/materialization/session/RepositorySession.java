package sh.zolt.resolve.materialization.session;

import sh.zolt.cache.CachedArtifact;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.maven.repository.EffectiveRawPom;
import sh.zolt.maven.repository.MavenRepositoryClient;
import sh.zolt.maven.repository.RawPom;
import sh.zolt.maven.repository.RawPomParser;
import sh.zolt.maven.repository.RepositoryAccess;
import sh.zolt.maven.repository.RepositoryAccessPlanner;
import sh.zolt.maven.repository.RepositoryDownloadListener;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.lockfile.assembly.LockfileAssemblyContext;
import sh.zolt.resolve.materialization.MaterializedArtifact;
import sh.zolt.resolve.metrics.ResolveMetrics;
import sh.zolt.resolve.metrics.ResolverMetricsCollector;
import sh.zolt.resolve.metrics.ResolverMetricsSink;
import sh.zolt.resolve.metadata.DependencyMetadataSource;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import sh.zolt.resolve.metadata.platform.ProjectPlatformMetadataPlanner;
import sh.zolt.resolve.metadata.pom.PomMetadataPreloader;
import sh.zolt.resolve.request.DependencyRequest;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One project's view of a resolution session: its configuration, its managed versions, its metrics,
 * and the repositories its configuration says to fetch from.
 *
 * <p>Everything that is a function of the coordinate rather than of this project — POM bytes, parsed
 * POMs, effective models, artifact digests — lives in the {@link WorkspaceResolutionSession} instead,
 * so a command resolving many projects derives each of them once. A single-project resolve gets a
 * session of its own and behaves exactly as it did when it owned these caches directly.
 */
public final class RepositorySession implements DependencyMetadataSource, ResolverMetricsSink, LockfileAssemblyContext {
    private final ProjectConfig config;
    private final WorkspaceResolutionSession session;
    private final SharedRepositoryScope scope;
    private final RepositoryAccessPlanner repositoryAccessPlanner = new RepositoryAccessPlanner();
    private final RepositoryFetchCoordinator repositoryFetchCoordinator = new RepositoryFetchCoordinator();
    private final MavenRepositoryClient repositoryClient;
    private final RepositoryDownloadListener downloadProgressListener;
    private final ArtifactBatchMaterializer artifactBatchMaterializer = new ArtifactBatchMaterializer();
    private final PomMetadataPreloader pomMetadataPreloader = new PomMetadataPreloader();
    private final ProjectPlatformMetadataPlanner projectPlatformMetadataPlanner;
    private final ResolverMetricsCollector metricsCollector = new ResolverMetricsCollector();
    private Map<PackageId, ManagedVersion> projectManagedVersions;

    public RepositorySession(
            ProjectConfig config,
            Path cacheRoot,
            ResolveOptions options,
            CoordinateParser coordinateParser,
            MavenRepositoryClient repositoryClient,
            RawPomParser rawPomParser) {
        this(
                config,
                options,
                coordinateParser,
                repositoryClient,
                new WorkspaceResolutionSession(cacheRoot, options, rawPomParser));
    }

    public RepositorySession(
            ProjectConfig config,
            ResolveOptions options,
            CoordinateParser coordinateParser,
            MavenRepositoryClient repositoryClient,
            WorkspaceResolutionSession session) {
        this.config = config;
        this.session = session;
        this.scope = session.scopeFor(config);
        this.repositoryClient = repositoryClient;
        this.downloadProgressListener = options.artifactProgressListener()::onBytes;
        this.projectPlatformMetadataPlanner = new ProjectPlatformMetadataPlanner(coordinateParser);
    }

    @Override
    public EffectiveRawPom load(Coordinate coordinate) {
        return scope.effectivePom(coordinate, this::rawPom, metricsCollector);
    }

    @Override
    public void preload(List<Coordinate> coordinates) {
        pomMetadataPreloader.preload(
                coordinates,
                session.cache().downloadConcurrency(),
                session.cache().repositoryExecutionLane(),
                this::load);
    }

    @Override
    public ProjectConfig config() {
        return config;
    }

    private CachedArtifact getPom(Coordinate coordinate) {
        return scope.pomArtifact(coordinate, this::materializePom, metricsCollector);
    }

    public CachedArtifact getJar(Coordinate coordinate) {
        return session.artifactMaterializer().getJar(coordinate, this::fetchJar, metricsCollector);
    }

    CachedArtifact getArtifact(ArtifactDescriptor descriptor) {
        return session.artifactMaterializer().getArtifact(descriptor, this::fetchArtifact, metricsCollector);
    }

    @Override
    public String sourceFor(MaterializedArtifact artifact) {
        return session.artifactSources().getOrDefault(artifact.repositoryPath(), "maven-central");
    }

    @Override
    public MaterializedArtifact getPomArtifact(Coordinate coordinate) {
        return session.describe(getPom(coordinate));
    }

    @Override
    public Map<ArtifactDescriptor, MaterializedArtifact> getArtifacts(List<ArtifactDescriptor> descriptors) {
        return scope.materializedArtifacts(descriptors, this::materializeArtifacts, metricsCollector);
    }

    private Map<ArtifactDescriptor, MaterializedArtifact> materializeArtifacts(
            List<ArtifactDescriptor> descriptors) {
        return artifactBatchMaterializer.materialize(
                descriptors,
                session.cache().downloadConcurrency(),
                session.cache().repositoryExecutionLane(),
                descriptor -> session.describe(getArtifact(descriptor)));
    }

    public int downloadCount() {
        return metricsCollector.downloadCount();
    }

    public ResolveMetrics metrics() {
        return metricsCollector.metrics();
    }

    @Override
    public void addGraphTraversalNanos(long nanos) {
        metricsCollector.addGraphTraversalNanos(nanos);
    }

    @Override
    public void addVersionSelectionNanos(long nanos) {
        metricsCollector.addVersionSelectionNanos(nanos);
    }

    @Override
    public void addLockfileAssemblyNanos(long nanos) {
        metricsCollector.addLockfileAssemblyNanos(nanos);
    }

    public Map<PackageId, String> projectManagedVersions() {
        Map<PackageId, String> versions = new LinkedHashMap<>();
        projectManagedVersionDetails().forEach((packageId, managedVersion) ->
                versions.put(packageId, managedVersion.version()));
        return versions;
    }

    @Override
    public Map<PackageId, ManagedVersion> projectManagedVersionDetails() {
        if (projectManagedVersions != null) {
            return projectManagedVersions;
        }
        projectManagedVersions = projectPlatformMetadataPlanner.managedVersions(
                config,
                this::load);
        return projectManagedVersions;
    }

    public List<DependencyRequest> projectPlatformPropertiesRequests() {
        return projectPlatformMetadataPlanner.propertiesRequests(
                config,
                this::load);
    }

    private RawPom rawPom(Coordinate coordinate) {
        return scope.rawPom(coordinate, this::getPom, metricsCollector);
    }

    private CachedArtifact materializePom(Coordinate coordinate) {
        return session.artifactMaterializer().getPom(coordinate, this::fetchPom, metricsCollector);
    }

    private sh.zolt.maven.repository.RepositoryArtifact fetchPom(Coordinate coordinate) {
        return fetchFromRepositories(access ->
                repositoryClient.fetchPom(access.uri(), coordinate, access.authentication(), downloadProgressListener));
    }

    private sh.zolt.maven.repository.RepositoryArtifact fetchJar(Coordinate coordinate) {
        return fetchFromRepositories(access ->
                repositoryClient.fetchJar(access.uri(), coordinate, access.authentication(), downloadProgressListener));
    }

    private sh.zolt.maven.repository.RepositoryArtifact fetchArtifact(ArtifactDescriptor descriptor) {
        return fetchFromRepositories(access ->
                repositoryClient.fetchArtifact(access.uri(), descriptor, access.authentication(), downloadProgressListener));
    }

    private sh.zolt.maven.repository.RepositoryArtifact fetchFromRepositories(RepositoryFetchAction action) {
        return repositoryFetchCoordinator.fetch(repositoryAccesses(), action::fetch);
    }

    private List<RepositoryAccess> repositoryAccesses() {
        return repositoryAccessPlanner.plan(config);
    }
}
