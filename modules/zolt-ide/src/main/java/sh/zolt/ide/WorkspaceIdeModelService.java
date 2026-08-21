package sh.zolt.ide;

import sh.zolt.cache.ArtifactCacheException;
import sh.zolt.error.ActionableException;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.ContentAddressedLockCapability;
import sh.zolt.lockfile.WorkspaceGraphLockCapability;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.resolve.ResolveException;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceClasspathService;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class WorkspaceIdeModelService {
    private static final int SCHEMA_VERSION = 1;

    private final ManifestWorkspaceLoader workspaceLoader;
    private final IdeModelService ideModelService;
    private final ZoltLockfileReader lockfileReader;
    private final WorkspaceIdeClasspathPlanner classpathPlanner;
    private final WorkspaceResolveService workspaceResolveService;

    public WorkspaceIdeModelService() {
        this(new IdeModelService());
    }

    public WorkspaceIdeModelService(IdeModelService ideModelService) {
        this(
                new ManifestWorkspaceLoader(),
                ideModelService,
                new ZoltLockfileReader(),
                new WorkspaceClasspathService(),
                new WorkspaceResolveService());
    }

    WorkspaceIdeModelService(
            ManifestWorkspaceLoader workspaceLoader,
            IdeModelService ideModelService,
            ZoltLockfileReader lockfileReader,
            WorkspaceClasspathService workspaceClasspathService,
            WorkspaceResolveService workspaceResolveService) {
        this.workspaceLoader = workspaceLoader;
        this.ideModelService = ideModelService;
        this.lockfileReader = lockfileReader;
        this.classpathPlanner = new WorkspaceIdeClasspathPlanner(workspaceClasspathService);
        this.workspaceResolveService = workspaceResolveService;
    }

    public WorkspaceIdeModel export(Path startDirectory, Path cacheRoot, boolean checkLock, boolean offline) {
        return export(startDirectory, cacheRoot, checkLock, offline, IdeTimingRecorder.disabled());
    }

    public WorkspaceIdeModel export(
            Path startDirectory,
            Path cacheRoot,
            boolean checkLock,
            boolean offline,
            IdeTimingRecorder timings) {
        Path start = startDirectory.toAbsolutePath().normalize();
        IdeTimingRecorder recorder = timings == null ? IdeTimingRecorder.disabled() : timings;
        try {
            Optional<Workspace> discovered = recorder.measure(
                    "discover ide workspace",
                    () -> workspaceLoader.discover(start));
            if (discovered.isEmpty()) {
                return missingWorkspace(start);
            }
            Workspace workspace = discovered.orElseThrow();
            WorkspaceLockState lockState = recorder.measure(
                    "read workspace ide lock",
                    () -> workspaceLockState(workspace, cacheRoot, checkLock, offline),
                    WorkspaceIdeModelService::workspaceLockAttributes);
            List<WorkspaceIdeModel.ProjectModel> projects = recorder.measure(
                    "export workspace ide projects",
                    () -> projectModels(workspace, cacheRoot, lockState, recorder),
                    WorkspaceIdeModelService::workspaceProjectAttributes);
            List<WorkspaceIdeModel.ProjectEdge> edges = recorder.measure(
                    "export workspace ide edges",
                    () -> projectEdges(workspace),
                    WorkspaceIdeModelService::workspaceEdgeAttributes);
            return recorder.measure(
                    "assemble workspace ide model",
                    () -> new WorkspaceIdeModel(
                            SCHEMA_VERSION,
                            workspaceInfo(workspace),
                            projects,
                            edges,
                            lockState.diagnostics()),
                    WorkspaceIdeModelService::workspaceIdeModelAttributes);
        } catch (WorkspaceConfigException exception) {
            return invalidWorkspace(start, exception);
        }
    }

    private WorkspaceIdeModel.WorkspaceInfo workspaceInfo(Workspace workspace) {
        return new WorkspaceIdeModel.WorkspaceInfo(
                workspace.config().name(),
                workspace.root(),
                workspace.configPath(),
                workspace.root().resolve("zolt.lock"),
                workspace.config().members(),
                workspace.config().defaultMembers(),
                workspace.buildOrder());
    }

    private List<WorkspaceIdeModel.ProjectModel> projectModels(
            Workspace workspace,
            Path cacheRoot,
            WorkspaceLockState lockState,
            IdeTimingRecorder timings) {
        List<WorkspaceIdeModel.ProjectModel> projects = new ArrayList<>();
        Map<String, IdeModel.ClasspathInfo> classpathsByMember = timings.measure(
                "plan workspace ide classpaths",
                () -> classpathPlanner.classpaths(workspace, cacheRoot, lockState.lockfile()),
                WorkspaceIdeModelService::workspaceClasspathAttributes);
        for (WorkspaceMember member : workspace.members()) {
            projects.add(new WorkspaceIdeModel.ProjectModel(
                    member.path(),
                    ideModelService.exportWithClasspaths(
                            member.directory(),
                            workspace.root().resolve("zolt.lock"),
                            classpathsByMember.get(member.path()),
                            List.of())));
        }
        return List.copyOf(projects);
    }

    private static Map<String, String> workspaceLockAttributes(WorkspaceLockState lockState) {
        return Map.of(
                "lockfilePresent", Boolean.toString(lockState.lockfile() != null),
                "diagnostics", Integer.toString(lockState.diagnostics().size()));
    }

    private static Map<String, String> workspaceProjectAttributes(List<WorkspaceIdeModel.ProjectModel> projects) {
        return Map.of("projects", Integer.toString(projects.size()));
    }

    private static Map<String, String> workspaceEdgeAttributes(List<WorkspaceIdeModel.ProjectEdge> edges) {
        return Map.of("edges", Integer.toString(edges.size()));
    }

    private static Map<String, String> workspaceClasspathAttributes(Map<String, IdeModel.ClasspathInfo> classpathsByMember) {
        int compileEntries = 0;
        int runtimeEntries = 0;
        int testEntries = 0;
        for (IdeModel.ClasspathInfo classpaths : classpathsByMember.values()) {
            compileEntries += classpaths.compile().size();
            runtimeEntries += classpaths.runtime().size();
            testEntries += classpaths.test().size();
        }
        return Map.of(
                "members", Integer.toString(classpathsByMember.size()),
                "compileClasspathEntries", Integer.toString(compileEntries),
                "runtimeClasspathEntries", Integer.toString(runtimeEntries),
                "testClasspathEntries", Integer.toString(testEntries));
    }

    private static Map<String, String> workspaceIdeModelAttributes(WorkspaceIdeModel model) {
        return Map.of(
                "projects", Integer.toString(model.projects().size()),
                "edges", Integer.toString(model.edges().size()),
                "diagnostics", Integer.toString(model.diagnostics().size()));
    }

    private WorkspaceLockState workspaceLockState(
            Workspace workspace,
            Path cacheRoot,
            boolean checkLock,
            boolean offline) {
        Path lockfilePath = workspace.root().resolve("zolt.lock").normalize();
        if (!Files.exists(lockfilePath)) {
            return new WorkspaceLockState(
                    null,
                    List.of(new IdeModel.Diagnostic(
                            "error",
                            "LOCKFILE_MISSING",
                            "Could not find workspace zolt.lock.",
                            lockfilePath,
                            "Run zolt resolve --workspace.")));
        }

        try {
            ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
            if (!WorkspaceGraphLockCapability.supportsMemberGraphEvidence(lockfile)) {
                return new WorkspaceLockState(
                        null,
                        List.of(new IdeModel.Diagnostic(
                                "error",
                                "LOCKFILE_GRAPH_SCHEMA_OUTDATED",
                                "Workspace zolt.lock version "
                                        + lockfile.version()
                                        + " lacks version "
                                        + WorkspaceGraphLockCapability.MINIMUM_VERSION
                                        + " member-qualified optional-boundary evidence.",
                                lockfilePath,
                                "Run zolt resolve --workspace.")));
            }
            List<IdeModel.Diagnostic> diagnostics = new ArrayList<>();
            if (checkLock && !ContentAddressedLockCapability.supportsArtifactCachePaths(lockfile)) {
                diagnostics.add(new IdeModel.Diagnostic(
                        "error",
                        "LOCKFILE_MIGRATION_REQUIRED",
                        "Workspace zolt.lock version "
                                + lockfile.version()
                                + " predates the version "
                                + ContentAddressedLockCapability.MINIMUM_VERSION
                                + " content-addressed artifact cache path contract required by this Zolt.",
                        lockfilePath,
                        "Run zolt resolve --workspace."));
                return new WorkspaceLockState(lockfile, diagnostics);
            }
            if (checkLock) {
                checkWorkspaceLockFreshness(workspace, cacheRoot, offline, lockfilePath, diagnostics);
            }
            return new WorkspaceLockState(lockfile, diagnostics);
        } catch (LockfileReadException exception) {
            return new WorkspaceLockState(
                    null,
                    List.of(new IdeModel.Diagnostic(
                            "error",
                            "LOCKFILE_UNREADABLE",
                            exception.getMessage(),
                            lockfilePath,
                            "Run zolt resolve --workspace.")));
        }
    }

    private void checkWorkspaceLockFreshness(
            Workspace workspace,
            Path cacheRoot,
            boolean offline,
            Path lockfilePath,
            List<IdeModel.Diagnostic> diagnostics) {
        try {
            workspaceResolveService.resolve(workspace.root(), cacheRoot, true, offline);
        } catch (ResolveException exception) {
            diagnostics.add(new IdeModel.Diagnostic(
                    "error",
                    lockDiagnosticCode(exception),
                    exception.getMessage(),
                    lockfilePath,
                    "Run zolt resolve --workspace."));
        } catch (ArtifactCacheException exception) {
            diagnostics.add(new IdeModel.Diagnostic(
                    "error",
                    "LOCKFILE_CHECK_UNAVAILABLE",
                    exception.getMessage(),
                    lockfilePath,
                    "Run zolt resolve --workspace without --offline to seed the cache, then retry zolt ide model --workspace --offline."));
        } catch (ActionableException exception) {
            diagnostics.add(new IdeModel.Diagnostic(
                    "error",
                    "LOCKFILE_MIGRATION_REQUIRED",
                    exception.getMessage(),
                    lockfilePath,
                    "Run zolt resolve --workspace."));
        }
    }

    private static String lockDiagnosticCode(ResolveException exception) {
        return exception.getMessage().contains("out of date")
                ? "LOCKFILE_STALE"
                : "LOCKFILE_CHECK_FAILED";
    }

    private static List<WorkspaceIdeModel.ProjectEdge> projectEdges(Workspace workspace) {
        List<WorkspaceIdeModel.ProjectEdge> edges = new ArrayList<>();
        for (WorkspaceProjectEdge edge : workspace.edges()) {
            edges.add(new WorkspaceIdeModel.ProjectEdge(
                    edge.from(),
                    edge.to(),
                    edge.scope(),
                    edge.coordinate(),
                    edge.exported()));
        }
        return List.copyOf(edges);
    }

    private static WorkspaceIdeModel missingWorkspace(Path start) {
        return new WorkspaceIdeModel(
                SCHEMA_VERSION,
                emptyWorkspaceInfo(),
                List.of(),
                List.of(),
                List.of(new IdeModel.Diagnostic(
                        "error",
                        "WORKSPACE_NOT_FOUND",
                        "Could not find workspace config.",
                        start,
                        "Run from a workspace directory or add zolt.toml with [workspace].")));
    }

    private static WorkspaceIdeModel invalidWorkspace(Path start, WorkspaceConfigException exception) {
        return new WorkspaceIdeModel(
                SCHEMA_VERSION,
                emptyWorkspaceInfo(),
                List.of(),
                List.of(),
                List.of(new IdeModel.Diagnostic(
                        "error",
                        "WORKSPACE_INVALID",
                        exception.getMessage(),
                        start,
                        "Fix workspace config and run zolt ide model --workspace --format json again.")));
    }

    private static WorkspaceIdeModel.WorkspaceInfo emptyWorkspaceInfo() {
        return new WorkspaceIdeModel.WorkspaceInfo(null, null, null, null, List.of(), List.of(), List.of());
    }

    private record WorkspaceLockState(ZoltLockfile lockfile, List<IdeModel.Diagnostic> diagnostics) {
        private WorkspaceLockState {
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
