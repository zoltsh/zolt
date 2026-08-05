package sh.zolt.workspace.service;

import sh.zolt.build.BuildService;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.provenance.BuildProvenanceSource;
import sh.zolt.project.PackageMode;
import sh.zolt.resolve.ResolveService;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorkspaceBuildService {
    private final WorkspaceBuildPlanner buildPlanner;
    private final WorkspaceClasspathService workspaceClasspathService;
    private final WorkspaceMemberBuildExecutor memberBuildExecutor;
    private final WorkspaceDirtyPlanner dirtyPlanner = new WorkspaceDirtyPlanner();

    public WorkspaceBuildService() {
        this(new JdkDetector());
    }

    public WorkspaceBuildService(ResolveService resolveService) {
        this(new JdkDetector(), resolveService);
    }

    public WorkspaceBuildService(ResolveService resolveService, BuildProvenanceSource provenanceSource) {
        this(new JdkDetector(), resolveService, provenanceSource);
    }

    WorkspaceBuildService(JdkChecker jdkDetector) {
        this(jdkDetector, new ResolveService());
    }

    public WorkspaceBuildService(JdkChecker jdkDetector, ResolveService resolveService) {
        this(jdkDetector, resolveService, BuildProvenanceSource.empty());
    }

    public WorkspaceBuildService(
            JdkChecker jdkDetector,
            ResolveService resolveService,
            BuildProvenanceSource provenanceSource) {
        this(
                new WorkspaceBuildPlanner(resolveService),
                new WorkspaceClasspathService(),
                new WorkspaceMemberBuildExecutor(
                        new BuildService(jdkDetector, resolveService, provenanceSource),
                        WorkspaceJdkCheckerResolver.fixed(jdkDetector),
                        new WorkspaceBuildBatchPlanner()));
    }

    WorkspaceBuildService(
            WorkspaceBuildPlanner buildPlanner,
            WorkspaceClasspathService workspaceClasspathService,
            WorkspaceMemberBuildExecutor memberBuildExecutor) {
        this.buildPlanner = buildPlanner;
        this.workspaceClasspathService = workspaceClasspathService;
        this.memberBuildExecutor = memberBuildExecutor;
    }

    public WorkspaceBuildService withJdkCheckers(WorkspaceJdkCheckerResolver jdkCheckers) {
        return new WorkspaceBuildService(
                buildPlanner,
                workspaceClasspathService,
                memberBuildExecutor.withJdkCheckers(jdkCheckers));
    }

    public WorkspaceBuildService withBuildCache(BuildCacheService buildCacheService) {
        return new WorkspaceBuildService(
                buildPlanner,
                workspaceClasspathService,
                memberBuildExecutor.withBuildCache(buildCacheService));
    }

    public WorkspaceBuildResult build(Path startDirectory, Path cacheRoot, boolean offline) {
        return build(startDirectory, cacheRoot, offline, WorkspaceSelectionRequest.defaults());
    }

    public WorkspaceBuildResult build(
            Path startDirectory,
            Path cacheRoot,
            boolean offline,
            WorkspaceSelectionRequest selectionRequest) {
        return WorkspaceMutationLock.withWorkspaceLock(
                startDirectory,
                () -> build(
                        planBuild(startDirectory, cacheRoot, offline, selectionRequest),
                        cacheRoot));
    }

    public WorkspaceBuildPlan planBuild(
            Path startDirectory,
            Path cacheRoot,
            boolean offline,
            WorkspaceSelectionRequest selectionRequest) {
        return planBuild(WorkspacePlanTarget.at(startDirectory), cacheRoot, offline, selectionRequest);
    }

    public WorkspaceBuildPlan planBuild(
            WorkspacePlanTarget target,
            Path cacheRoot,
            boolean offline,
            WorkspaceSelectionRequest selectionRequest) {
        return planLocked(target, cacheRoot, offline, selectionRequest, false);
    }

    WorkspaceBuildPlan planTestBuild(
            WorkspacePlanTarget target,
            Path cacheRoot,
            boolean offline,
            WorkspaceSelectionRequest selectionRequest) {
        return planLocked(target, cacheRoot, offline, selectionRequest, true);
    }

    /**
     * Takes the mutation lock the plan is read under. A discovered workspace already names its root,
     * so it locks that directly; otherwise the root is located and confirmed under the lease.
     */
    private WorkspaceBuildPlan planLocked(
            WorkspacePlanTarget target,
            Path cacheRoot,
            boolean offline,
            WorkspaceSelectionRequest selectionRequest,
            boolean includeTestLanes) {
        return target.discovered()
                .map(workspace -> WorkspaceMutationLock.withLock(
                        workspace.root(),
                        () -> buildPlanner.plan(
                                workspace, cacheRoot, offline, selectionRequest, includeTestLanes)))
                .orElseGet(() -> WorkspaceMutationLock.withWorkspaceLock(
                        target.startDirectory(),
                        () -> buildPlanner.plan(
                                target.startDirectory(),
                                cacheRoot,
                                offline,
                                selectionRequest,
                                includeTestLanes)));
    }

    public WorkspaceBuildResult build(WorkspaceBuildPlan plan, Path cacheRoot) {
        return build(plan, cacheRoot, WorkspaceBuildRequirements.mainBuild());
    }

    public WorkspaceBuildResult build(
            WorkspaceBuildPlan plan,
            Path cacheRoot,
            WorkspaceBuildRequirements selectedRequirements) {
        Map<String, WorkspaceBuildRequirements> requirementsByMember = new LinkedHashMap<>();
        for (String member : plan.selection().includedMembers()) {
            requirementsByMember.put(
                    member,
                    plan.selection().selectedMembers().contains(member)
                            ? selectedRequirements
                            : WorkspaceBuildRequirements.mainBuild());
        }
        return build(plan, cacheRoot, requirementsByMember);
    }

    WorkspaceBuildResult build(
            WorkspaceBuildPlan plan,
            Path cacheRoot,
            Set<String> fullClasspathMembers) {
        Map<String, WorkspaceBuildRequirements> requirementsByMember = new LinkedHashMap<>();
        for (String member : plan.selection().includedMembers()) {
            requirementsByMember.put(
                    member,
                    fullClasspathMembers.contains(member)
                            ? WorkspaceBuildRequirements.testRun()
                            : WorkspaceBuildRequirements.mainBuild());
        }
        return build(plan, cacheRoot, requirementsByMember);
    }

    private WorkspaceBuildResult build(
            WorkspaceBuildPlan plan,
            Path cacheRoot,
            Map<String, WorkspaceBuildRequirements> requirementsByMember) {
        try (WorkspaceMutationLock ignored =
                WorkspaceMutationLock.acquire(plan.workspace().root())) {
            return buildLocked(plan, cacheRoot, requirementsByMember);
        }
    }

    private WorkspaceBuildResult buildLocked(
            WorkspaceBuildPlan plan,
            Path cacheRoot,
            Map<String, WorkspaceBuildRequirements> requirementsByMember) {
        WorkspaceExecutionContext context =
                executionContext(plan.requireInputsCurrent(), cacheRoot);
        Workspace workspace = plan.workspace();
        WorkspaceSelection selection = plan.selection();
        Map<String, WorkspaceMember> membersByPath = membersByPath(workspace);
        WorkspaceBuildRequirementResolver requirementResolver =
                new WorkspaceBuildRequirementResolver();
        Map<String, WorkspaceBuildRequirements> resolvedRequirements =
                new LinkedHashMap<>();
        for (String member : selection.includedMembers()) {
            resolvedRequirements.put(
                    member,
                    requirementResolver.forMember(
                            requirementsByMember.getOrDefault(
                                    member,
                                    WorkspaceBuildRequirements.mainBuild()),
                            membersByPath.get(member).config()));
        }
        Map<String, String> toolchainIdentitiesByMember =
                new LinkedHashMap<>();
        for (String member : selection.includedMembers()) {
            WorkspaceMember workspaceMember = membersByPath.get(member);
            toolchainIdentitiesByMember.put(
                    member,
                    workspaceMember.config().packageSettings().mode() == PackageMode.BOM
                            ? "not-applicable:bom"
                            : context.toolchainIndex().compileIdentity(
                                    memberBuildExecutor.jdkCheckers(),
                                    workspace,
                                    workspaceMember));
        }
        Map<String, ClasspathSet> classpathsByMember = workspaceClasspathService.classpathsForMembers(
                context,
                selection.includedMembers(),
                resolvedRequirements);
        List<String> packageMembers = selection.includedMembers().stream()
                .filter(member -> resolvedRequirements
                        .getOrDefault(member, WorkspaceBuildRequirements.mainBuild())
                        .packageInputs())
                .toList();
        Map<String, List<ResolvedClasspathPackage>> calculatedPackages =
                workspaceClasspathService.classpathPackagesForMembers(context, packageMembers);
        Map<String, List<ResolvedClasspathPackage>> classpathPackagesByMember = new LinkedHashMap<>();
        for (String member : selection.includedMembers()) {
            classpathPackagesByMember.put(
                    member,
                    calculatedPackages.getOrDefault(member, List.of()));
        }
        WorkspaceDirtyPlan dirtyPlan = dirtyPlanner.plan(
                context,
                selection,
                membersByPath,
                classpathsByMember,
                classpathPackagesByMember,
                resolvedRequirements,
                toolchainIdentitiesByMember);
        long memberExecutionStarted = System.nanoTime();
        WorkspaceMemberBuildExecutor.Result execution = memberBuildExecutor.build(
                context,
                workspace,
                selection,
                membersByPath,
                classpathsByMember,
                classpathPackagesByMember,
                dirtyPlan);
        context.addMemberExecutionNanos(elapsedSince(memberExecutionStarted));
        context.addSchedulerMetrics(
                execution.schedulerIdleNanos(),
                execution.readyQueuePeak());
        context.addDirtyPlanMetrics(
                selection.includedMembers().size(),
                execution.pipelineInvocations());
        dirtyPlanner.writeCurrent(
                context,
                selection,
                membersByPath,
                classpathsByMember,
                classpathPackagesByMember,
                resolvedRequirements,
                toolchainIdentitiesByMember,
                dirtyPlan);
        return new WorkspaceBuildResult(
                plan.resolveResult(),
                execution.results(),
                execution.waveCount(),
                execution.maxWorkers(),
                context.metrics());
    }

    private static WorkspaceExecutionContext executionContext(
            WorkspaceBuildPlan plan,
            Path cacheRoot) {
        Path requestedCacheRoot = cacheRoot.toAbsolutePath().normalize();
        if (plan.executionContext().cacheRoot().equals(requestedCacheRoot)) {
            return plan.executionContext();
        }
        return new WorkspaceExecutionContext(
                plan.workspace(), plan.lockfile(), requestedCacheRoot);
    }

    private static long elapsedSince(long started) {
        return Math.max(0L, System.nanoTime() - started);
    }

    private static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }
}
