package sh.zolt.workspace.service;

import sh.zolt.build.BuildService;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.WorkspaceGraphLockCapability;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.provenance.BuildProvenanceSource;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class WorkspaceBuildService {
    private final WorkspaceDiscoveryService workspaceDiscoveryService;
    private final WorkspaceResolveService workspaceResolveService;
    private final ZoltLockfileReader lockfileReader;
    private final WorkspaceClasspathService workspaceClasspathService;
    private final WorkspaceMemberSelector memberSelector;
    private final WorkspaceMemberBuildExecutor memberBuildExecutor;

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
                new WorkspaceDiscoveryService(),
                new WorkspaceResolveService(resolveService),
                new ZoltLockfileReader(),
                new WorkspaceClasspathService(),
                new WorkspaceMemberSelector(),
                new WorkspaceMemberBuildExecutor(
                        new BuildService(jdkDetector, resolveService, provenanceSource),
                        WorkspaceJdkCheckerResolver.fixed(jdkDetector),
                        new WorkspaceBuildBatchPlanner()));
    }

    WorkspaceBuildService(
            WorkspaceDiscoveryService workspaceDiscoveryService,
            WorkspaceResolveService workspaceResolveService,
            ZoltLockfileReader lockfileReader,
            WorkspaceClasspathService workspaceClasspathService,
            WorkspaceMemberSelector memberSelector,
            WorkspaceMemberBuildExecutor memberBuildExecutor) {
        this.workspaceDiscoveryService = workspaceDiscoveryService;
        this.workspaceResolveService = workspaceResolveService;
        this.lockfileReader = lockfileReader;
        this.workspaceClasspathService = workspaceClasspathService;
        this.memberSelector = memberSelector;
        this.memberBuildExecutor = memberBuildExecutor;
    }

    public WorkspaceBuildService withJdkCheckers(WorkspaceJdkCheckerResolver jdkCheckers) {
        return new WorkspaceBuildService(
                workspaceDiscoveryService,
                workspaceResolveService,
                lockfileReader,
                workspaceClasspathService,
                memberSelector,
                memberBuildExecutor.withJdkCheckers(jdkCheckers));
    }

    public WorkspaceBuildService withBuildCache(BuildCacheService buildCacheService) {
        return new WorkspaceBuildService(
                workspaceDiscoveryService,
                workspaceResolveService,
                lockfileReader,
                workspaceClasspathService,
                memberSelector,
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
        return build(planBuild(startDirectory, cacheRoot, offline, selectionRequest), cacheRoot);
    }

    public WorkspaceBuildPlan planBuild(
            Path startDirectory,
            Path cacheRoot,
            boolean offline,
            WorkspaceSelectionRequest selectionRequest) {
        return planBuild(startDirectory, cacheRoot, offline, selectionRequest, false);
    }

    WorkspaceBuildPlan planTestBuild(
            Path startDirectory,
            Path cacheRoot,
            boolean offline,
            WorkspaceSelectionRequest selectionRequest) {
        return planBuild(startDirectory, cacheRoot, offline, selectionRequest, true);
    }

    private WorkspaceBuildPlan planBuild(
            Path startDirectory,
            Path cacheRoot,
            boolean offline,
            WorkspaceSelectionRequest selectionRequest,
        boolean includeTestLanes) {
        Path start = startDirectory.toAbsolutePath().normalize();
        long discoveryStarted = System.nanoTime();
        Workspace workspace = workspaceDiscoveryService.discover(start).orElseThrow(() -> ResolveException.actionable(
                "Could not find workspace config.",
                "Run `zolt build --workspace` from a workspace directory or add zolt.toml with [workspace]."));
        long discoveryNanos = elapsedSince(discoveryStarted);
        long selectionStarted = System.nanoTime();
        WorkspaceSelection selection = includeTestLanes
                ? memberSelector.select(workspace, selectionRequest)
                : memberSelector.selectMain(workspace, selectionRequest);
        long selectionNanos = elapsedSince(selectionStarted);
        Path lockfilePath = workspace.root().resolve("zolt.lock");
        Optional<ResolveResult> resolveResult = Optional.empty();
        long resolutionNanos = 0L;
        if (!Files.isRegularFile(lockfilePath)) {
            long resolutionStarted = System.nanoTime();
            resolveResult = Optional.of(workspaceResolveService.resolve(
                    start,
                    cacheRoot,
                    false,
                    offline,
                    "zolt build --workspace"));
            resolutionNanos = elapsedSince(resolutionStarted);
        }

        long lockfileReadStarted = System.nanoTime();
        ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
        WorkspaceGraphLockCapability.requireMemberGraphEvidence(lockfile);
        long lockfileReadNanos = elapsedSince(lockfileReadStarted);
        return new WorkspaceBuildPlan(
                workspace,
                selection,
                resolveResult,
                lockfile,
                new WorkspaceExecutionContext(workspace, lockfile, cacheRoot),
                new WorkspacePlanMetrics(
                        discoveryNanos,
                        selectionNanos,
                        resolutionNanos,
                        lockfileReadNanos,
                        workspace.members().size(),
                        workspace.edges().size(),
                        lockfile.packages().size()));
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
        WorkspaceExecutionContext context = executionContext(plan, cacheRoot);
        Workspace workspace = plan.workspace();
        WorkspaceSelection selection = plan.selection();
        Map<String, WorkspaceMember> membersByPath = membersByPath(workspace);
        Map<String, ClasspathSet> classpathsByMember = workspaceClasspathService.classpathsForMembers(
                context,
                selection.includedMembers(),
                requirementsByMember);
        List<String> packageMembers = selection.includedMembers().stream()
                .filter(member -> requirementsByMember
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
        long memberExecutionStarted = System.nanoTime();
        WorkspaceMemberBuildExecutor.Result execution = memberBuildExecutor.build(
                workspace,
                selection,
                membersByPath,
                classpathsByMember,
                classpathPackagesByMember);
        context.addMemberExecutionNanos(elapsedSince(memberExecutionStarted));
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
        return new WorkspaceExecutionContext(plan.workspace(), plan.lockfile(), requestedCacheRoot);
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
