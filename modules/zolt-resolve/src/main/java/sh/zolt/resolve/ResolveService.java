package sh.zolt.resolve;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileWriter;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.maven.repository.MavenRepositoryClient;
import sh.zolt.maven.repository.RawPomParser;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.framework.FrameworkDependencyRequestPlanRequestAssembler;
import sh.zolt.resolve.framework.FrameworkDependencyRequestPlanner;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.lockfile.assembly.ExecToolResolution;
import sh.zolt.resolve.lockfile.assembly.LockfileAssembler;
import sh.zolt.resolve.lockfile.persistence.ResolveLockfilePersistence;
import sh.zolt.resolve.materialization.session.RepositorySession;
import sh.zolt.resolve.metrics.ResolveMetrics;
import sh.zolt.resolve.metadata.DependencyMetadataSource;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.DependencyRequestPlanner;
import sh.zolt.resolve.request.tooling.GeneratedSourceToolingDependencyContributor;
import sh.zolt.resolve.traversal.DependencyGraphTraverser;
import sh.zolt.resolve.traversal.DependencyRelocator;
import sh.zolt.resolve.version.VersionSelectionResult;
import sh.zolt.resolve.version.VersionSelector;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ResolveService {
    private final CoordinateParser coordinateParser;
    private final MavenRepositoryClient repositoryClient;
    private final RawPomParser rawPomParser;
    private final DependencyGraphResolver graphResolver;
    private final DependencyRequestPlanner dependencyRequestPlanner;
    private final LockfileAssembler lockfileAssembler;
    private final ResolveLockfilePersistence lockfilePersistence;
    private final FrameworkDependencyRequestPlanRequestAssembler frameworkPlanRequestAssembler =
            new FrameworkDependencyRequestPlanRequestAssembler();
    private final FrameworkDependencyRequestPlanner frameworkDependencyRequestPlanner;

    public ResolveService() {
        this(FrameworkDependencyRequestPlanner.none());
    }

    public ResolveService(FrameworkDependencyRequestPlanner frameworkDependencyRequestPlanner) {
        this(new CoordinateParser(), frameworkDependencyRequestPlanner);
    }

    public ResolveService(
            FrameworkDependencyRequestPlanner frameworkDependencyRequestPlanner,
            MavenRepositoryClient repositoryClient) {
        this(new CoordinateParser(), repositoryClient, frameworkDependencyRequestPlanner);
    }

    private ResolveService(
            CoordinateParser coordinateParser,
            FrameworkDependencyRequestPlanner frameworkDependencyRequestPlanner) {
        this(coordinateParser, new MavenRepositoryClient(), frameworkDependencyRequestPlanner);
    }

    private ResolveService(
            CoordinateParser coordinateParser,
            MavenRepositoryClient repositoryClient,
            FrameworkDependencyRequestPlanner frameworkDependencyRequestPlanner) {
        this(
                coordinateParser,
                repositoryClient,
                new RawPomParser(),
                DependencyGraphTraverser::new,
                new VersionSelector(),
                new ZoltLockfileWriter(),
                defaultDependencyRequestPlanner(coordinateParser),
                new LockfileAssembler(coordinateParser),
                frameworkDependencyRequestPlanner);
    }

    ResolveService(
            CoordinateParser coordinateParser,
            MavenRepositoryClient repositoryClient,
            RawPomParser rawPomParser,
            DependencyGraphTraverserFactory graphTraverserFactory,
            VersionSelector versionSelector,
            ZoltLockfileWriter lockfileWriter,
            DependencyRequestPlanner dependencyRequestPlanner,
            LockfileAssembler lockfileAssembler,
            FrameworkDependencyRequestPlanner frameworkDependencyRequestPlanner) {
        this.coordinateParser = coordinateParser;
        this.repositoryClient = repositoryClient;
        this.rawPomParser = rawPomParser;
        this.graphResolver = new DependencyGraphResolver(graphTraverserFactory, versionSelector);
        this.dependencyRequestPlanner = dependencyRequestPlanner == null
                ? defaultDependencyRequestPlanner(coordinateParser)
                : dependencyRequestPlanner;
        this.lockfileAssembler = lockfileAssembler == null
                ? new LockfileAssembler(coordinateParser)
                : lockfileAssembler;
        this.lockfilePersistence = new ResolveLockfilePersistence(
                lockfileWriter == null ? new ZoltLockfileWriter() : lockfileWriter);
        this.frameworkDependencyRequestPlanner = frameworkDependencyRequestPlanner == null
                ? FrameworkDependencyRequestPlanner.none()
                : frameworkDependencyRequestPlanner;
    }

    private static DependencyRequestPlanner defaultDependencyRequestPlanner(CoordinateParser coordinateParser) {
        return new DependencyRequestPlanner(coordinateParser);
    }

    public ResolveResult resolve(Path projectDirectory, ProjectConfig config, Path cacheRoot) {
        return resolve(projectDirectory, config, cacheRoot, false);
    }

    public ResolveResult resolve(Path projectDirectory, ProjectConfig config, Path cacheRoot, boolean locked) {
        return resolve(projectDirectory, config, cacheRoot, locked, false);
    }

    public ResolveResult resolve(Path projectDirectory, ProjectConfig config, Path cacheRoot, boolean locked, boolean offline) {
        return resolve(projectDirectory, config, cacheRoot, locked, ResolveOptions.offline(offline));
    }

    public ResolveResult resolve(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            boolean locked,
            ResolveOptions options) {
        Path lockfilePath = lockfilePersistence.lockfilePath(projectDirectory);
        options = lockfilePersistence.prepare(lockfilePath, locked, options);

        ResolveOutput output = resolveLockfile(config, cacheRoot, options);
        ZoltLockfile lockfile = output.lockfile();
        ResolveMetrics metrics = lockfilePersistence.persist(
                lockfilePath,
                lockfile,
                output.metrics(),
                locked,
                options);
        return new ResolveResult(
                lockfile.packages().size(),
                output.downloadCount(),
                lockfile.conflicts().size(),
                lockfilePath,
                metrics);
    }

    public ResolveResult resolveWithCoverageTooling(Path projectDirectory, ProjectConfig config, Path cacheRoot) {
        return resolve(projectDirectory, config, cacheRoot, false, ResolveOptions.defaults().withCoverageTooling());
    }

    public ResolveOutput resolveLockfile(ProjectConfig config, Path cacheRoot, boolean offline) {
        return resolveLockfile(config, cacheRoot, ResolveOptions.offline(offline));
    }

    public ResolveOutput resolveLockfile(ProjectConfig config, Path cacheRoot, ResolveOptions options) {
        RepositorySession context =
                new RepositorySession(config, cacheRoot, options, coordinateParser, repositoryClient, rawPomParser);
        Map<PackageId, ManagedVersion> managedVersionDetails = context.projectManagedVersionDetails();
        Map<PackageId, String> managedVersions = context.projectManagedVersions();
        SnapshotAllowance snapshotAllowance =
                new SnapshotAllowance(options.workspaceMemberCoordinates(), options.repositoryOverlays());
        List<DependencyRequest> directRequests = dependencyRequestPlanner.plan(
                config,
                managedVersions,
                options.includeCoverageTooling(),
                options.retryCommand(),
                snapshotAllowance);
        directRequests = relocateDirectRequests(context, directRequests);
        // Exec tools resolve in isolation (Hole 1): keep TOOL_EXEC out of the shared project graph so a
        // tool's version line never mediates against another tool's or against compile/runtime, then lock
        // each tool's closure separately with a per-tool group qualifier.
        List<DependencyRequest> mainRequests = directRequests.stream()
                .filter(request -> request.scope() != DependencyScope.TOOL_EXEC)
                .toList();
        List<ExecToolResolution> execResolutions = resolveExecTools(
                context, managedVersionDetails, options, snapshotAllowance);
        DependencyGraphResolution initial = graphResolver.resolve(
                context,
                context.config().dependencyPolicy(),
                managedVersionDetails,
                mainRequests,
                context,
                options.retryCommand(),
                snapshotAllowance,
                options.versionOverrides());
        List<DependencyRequest> allRequests = new ArrayList<>(mainRequests);
        allRequests.addAll(frameworkDependencyRequestPlanner.plan(frameworkPlanRequestAssembler.assemble(
                context.config(),
                initial.graph(),
                initial.selection(),
                mainRequests,
                managedVersions,
                coordinate -> context.getJar(coordinate).cachePath(),
                context::projectPlatformPropertiesRequests)));
        DependencyGraphResolution resolved = allRequests.size() == mainRequests.size()
                ? initial
                : graphResolver.resolve(
                        context,
                        context.config().dependencyPolicy(),
                        managedVersionDetails,
                        allRequests,
                        context,
                        options.retryCommand(),
                        snapshotAllowance,
                        options.versionOverrides());
        VersionConflictPolicyEnforcer.enforce(
                context.config().dependencyPolicy(), resolved.selection(), execResolutions, options.retryCommand());
        ZoltLockfile lockfile = lockfile(context, resolved.graph(), resolved.selection(), allRequests, execResolutions);
        return new ResolveOutput(
                lockfile,
                context.downloadCount(),
                context.metrics(),
                ResolvedDependencyReachability.from(resolved.graph()));
    }

    private List<ExecToolResolution> resolveExecTools(
            RepositorySession context,
            Map<PackageId, ManagedVersion> managedVersionDetails,
            ResolveOptions options,
            SnapshotAllowance snapshotAllowance) {
        Map<String, List<DependencyRequest>> groups =
                new GeneratedSourceToolingDependencyContributor(coordinateParser).execToolRequestGroups(context.config());
        if (groups.isEmpty()) {
            return List.of();
        }
        DependencyRelocator relocator = new DependencyRelocator(context);
        List<ExecToolResolution> resolutions = new ArrayList<>();
        for (Map.Entry<String, List<DependencyRequest>> group : groups.entrySet()) {
            List<DependencyRequest> requests = group.getValue().stream()
                    .map(relocator::relocate)
                    .toList();
            DependencyGraphResolution resolution = graphResolver.resolve(
                    context,
                    context.config().dependencyPolicy(),
                    managedVersionDetails,
                    requests,
                    context,
                    options.retryCommand(),
                    snapshotAllowance);
            resolutions.add(new ExecToolResolution(
                    group.getKey(), resolution.graph(), resolution.selection(), requests));
        }
        return resolutions;
    }

    private List<DependencyRequest> relocateDirectRequests(
            RepositorySession context,
            List<DependencyRequest> directRequests) {
        DependencyRelocator relocator = new DependencyRelocator(context);
        return directRequests.stream()
                .map(relocator::relocate)
                .toList();
    }

    private ZoltLockfile lockfile(
            RepositorySession context,
            ResolutionGraph graph,
            VersionSelectionResult selection,
            List<DependencyRequest> directRequests,
            List<ExecToolResolution> execResolutions) {
        return lockfileAssembler.assemble(context, graph, selection, directRequests, execResolutions);
    }

    @FunctionalInterface
    interface DependencyGraphTraverserFactory {
        DependencyGraphTraverser create(
                DependencyMetadataSource source,
                DependencyPolicySettings dependencyPolicy,
                Map<PackageId, ManagedVersion> managedVersions,
                String retryCommand,
                SnapshotAllowance snapshotAllowance,
                Map<ResolutionVariant, String> versionOverrides,
                Map<ResolutionVariant, String> workspaceVersionOverrides);
    }

}
