package sh.zolt.ide;

import sh.zolt.lockfile.ProjectLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceClasspathService;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.publish.WorkspaceMemberDirectory;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class WorkspaceIdeModelService {
    private static final int SCHEMA_VERSION = 1;

    private final ManifestWorkspaceLoader workspaceLoader;
    private final WorkspaceMemberDirectory memberDirectory;
    private final IdeModelService ideModelService;
    private final ZoltLockfileReader lockfileReader;
    private final WorkspaceIdeClasspathPlanner classpathPlanner;
    private final WorkspaceIdeLockState lockStateReader;

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
        this.memberDirectory = new WorkspaceMemberDirectory(workspaceLoader);
        this.ideModelService = ideModelService;
        this.lockfileReader = lockfileReader;
        this.classpathPlanner = new WorkspaceIdeClasspathPlanner(workspaceClasspathService);
        this.lockStateReader = new WorkspaceIdeLockState(lockfileReader, workspaceResolveService);
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
            WorkspaceIdeLockState.WorkspaceLockState lockState = recorder.measure(
                    "read workspace ide lock",
                    () -> lockStateReader.read(workspace, cacheRoot, checkLock, offline),
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

    /**
     * The project model for the ONE member whose directory is {@code startDirectory}, or empty when
     * that directory is not a declared workspace member.
     *
     * <p>An IDE opening {@code apps/api} asks for that project's model, not the workspace's — but the
     * project it is asking about is a member, whose classpath is a projection of the workspace lock and
     * whose config only composes against the workspace root. Exporting it standalone reads
     * {@code apps/api/zolt.lock}, finds nothing, and hands the IDE an empty classpath with a
     * {@code LOCKFILE_MISSING} diagnostic — for a member whose dependencies are fully resolved at the
     * root. So this reuses the workspace export's own machinery and returns the member's slice of it.
     *
     * <p>{@code checkLock} therefore means workspace freshness here: a member's lock IS the workspace's,
     * so its staleness diagnostic names {@code zolt resolve --workspace}.
     */
    public Optional<IdeModel> exportMember(
            Path startDirectory,
            Path cacheRoot,
            boolean checkLock,
            boolean offline,
            IdeTimingRecorder timings) {
        Path start = startDirectory.toAbsolutePath().normalize();
        IdeTimingRecorder recorder = timings == null ? IdeTimingRecorder.disabled() : timings;
        Optional<WorkspaceMemberDirectory.Membership> membership = recorder.measure(
                "discover ide member",
                () -> memberDirectory.membershipAt(start));
        if (membership.isEmpty()) {
            return Optional.empty();
        }
        Workspace workspace = membership.orElseThrow().workspace();
        WorkspaceMember member = membership.orElseThrow().member();
        WorkspaceIdeLockState.WorkspaceLockState lockState = recorder.measure(
                "read workspace ide lock",
                () -> lockStateReader.read(workspace, cacheRoot, checkLock, offline),
                WorkspaceIdeModelService::workspaceLockAttributes);
        Map<String, IdeModel.ClasspathInfo> classpaths = recorder.measure(
                "plan workspace ide classpaths",
                () -> classpathPlanner.classpaths(
                        workspace, cacheRoot, lockState.lockfile(), List.of(member)),
                WorkspaceIdeModelService::workspaceClasspathAttributes);
        return Optional.of(recorder.measure(
                "export workspace ide projects",
                () -> ideModelService.exportWithClasspaths(
                        member.directory(),
                        ProjectLockfile.in(workspace.root()),
                        member.config(),
                        classpaths.get(member.path()),
                        lockState.diagnostics())));
    }

    private WorkspaceIdeModel.WorkspaceInfo workspaceInfo(Workspace workspace) {
        return new WorkspaceIdeModel.WorkspaceInfo(
                workspace.config().name(),
                workspace.root(),
                workspace.configPath(),
                ProjectLockfile.in(workspace.root()),
                workspace.config().members(),
                workspace.config().defaultMembers(),
                workspace.buildOrder());
    }

    private List<WorkspaceIdeModel.ProjectModel> projectModels(
            Workspace workspace,
            Path cacheRoot,
            WorkspaceIdeLockState.WorkspaceLockState lockState,
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
                            ProjectLockfile.in(workspace.root()),
                            member.config(),
                            classpathsByMember.get(member.path()),
                            List.of())));
        }
        return List.copyOf(projects);
    }

    private static Map<String, String> workspaceLockAttributes(WorkspaceIdeLockState.WorkspaceLockState lockState) {
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
}
