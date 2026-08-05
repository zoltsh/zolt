package sh.zolt.workspace.resolve;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileWriter;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.ResolveOutput;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.resolve.ResolveService;
import sh.zolt.resolve.metrics.ResolveMetrics;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class WorkspaceResolveService {
    private final WorkspaceDiscoveryService workspaceDiscoveryService;
    private final ResolveService resolveService;
    private final ZoltLockfileWriter lockfileWriter;
    private final WorkspaceResolveLockfilePersistence lockfilePersistence;
    private final WorkspacePolicyMerger policyMerger;
    private final WorkspaceLockfileAggregator lockfileAggregator;

    public WorkspaceResolveService() {
        this(new WorkspaceDiscoveryService(), new ResolveService(), new ZoltLockfileWriter(), new WorkspacePolicyMerger());
    }

    public WorkspaceResolveService(ResolveService resolveService) {
        this(new WorkspaceDiscoveryService(), resolveService, new ZoltLockfileWriter(), new WorkspacePolicyMerger());
    }

    WorkspaceResolveService(
            WorkspaceDiscoveryService workspaceDiscoveryService,
            ResolveService resolveService,
            ZoltLockfileWriter lockfileWriter) {
        this(workspaceDiscoveryService, resolveService, lockfileWriter, new WorkspacePolicyMerger());
    }

    WorkspaceResolveService(
            WorkspaceDiscoveryService workspaceDiscoveryService,
            ResolveService resolveService,
            ZoltLockfileWriter lockfileWriter,
            WorkspacePolicyMerger policyMerger) {
        this.workspaceDiscoveryService = workspaceDiscoveryService;
        this.resolveService = resolveService;
        this.lockfileWriter = lockfileWriter;
        this.lockfilePersistence =
                new WorkspaceResolveLockfilePersistence(lockfileWriter);
        this.policyMerger = policyMerger;
        this.lockfileAggregator = new WorkspaceLockfileAggregator();
    }

    public ResolveResult resolve(Path startDirectory, Path cacheRoot, boolean locked, boolean offline) {
        return resolve(startDirectory, cacheRoot, locked, offline, "zolt resolve --workspace");
    }

    public ResolveResult resolve(
            Path startDirectory,
            Path cacheRoot,
            boolean locked,
            boolean offline,
            String retryCommand) {
        return resolve(startDirectory, cacheRoot, locked, ResolveOptions.offline(offline).withRetryCommand(retryCommand));
    }

    public ResolveResult resolveWithCoverageTooling(Path startDirectory, Path cacheRoot) {
        return resolve(startDirectory, cacheRoot, false, ResolveOptions.defaults().withCoverageTooling());
    }

    /** Resolves coverage tooling from one already-captured workspace configuration snapshot. */
    public ResolveResult resolveWithCoverageTooling(Workspace workspace, Path cacheRoot) {
        return resolveCoverageSnapshot(workspace, cacheRoot).result();
    }

    /** Resolves coverage tooling and captures the exact lockfile bytes committed atomically. */
    public WorkspaceResolveSnapshot resolveCoverageSnapshot(
            Workspace workspace,
            Path cacheRoot) {
        return WorkspaceMutationLock.withLock(
                workspace.root(),
                () -> {
                    workspace.inputs().requireCurrent();
                    return resolveSnapshotLocked(
                            workspace, cacheRoot, false,
                            ResolveOptions.defaults()
                                    .withCoverageTooling());
                });
    }

    public ResolveResult resolve(Path startDirectory, Path cacheRoot, boolean locked, ResolveOptions options) {
        return WorkspaceMutationLock.withWorkspaceLock(
                startDirectory,
                () -> resolveLocked(startDirectory, cacheRoot, locked, options));
    }

    private ResolveResult resolveLocked(
            Path startDirectory,
            Path cacheRoot,
            boolean locked,
            ResolveOptions options) {
        Path start = startDirectory.toAbsolutePath().normalize();
        Workspace workspace = workspaceDiscoveryService.discover(start).orElseThrow(() -> new ResolveException(
                "Could not find workspace config. Run `zolt resolve --workspace` from a workspace directory or add zolt.toml with [workspace]."));
        return resolveLocked(workspace, cacheRoot, locked, options);
    }

    public ResolveResult resolve(
            Workspace workspace,
            Path cacheRoot,
            boolean locked,
            boolean offline,
            String retryCommand) {
        return WorkspaceMutationLock.withLock(
                workspace.root(),
                () -> {
                    workspace.inputs().requireCurrent();
                    return resolveLocked(
                            workspace,
                            cacheRoot,
                            locked,
                            ResolveOptions.offline(offline)
                                    .withRetryCommand(retryCommand));
                });
    }

    private ResolveResult resolveLocked(
            Workspace workspace,
            Path cacheRoot,
            boolean locked,
            ResolveOptions options) {
        return resolveSnapshotLocked(
                workspace,
                cacheRoot,
                locked,
                options).result();
    }

    private WorkspaceResolveSnapshot resolveSnapshotLocked(
            Workspace workspace,
            Path cacheRoot,
            boolean locked,
            ResolveOptions options) {
        Path lockfilePath = workspace.root().resolve("zolt.lock");
        if (locked && !Files.isRegularFile(lockfilePath)) {
            throw new ResolveException(
                    "Locked workspace resolve requires zolt.lock at "
                            + lockfilePath
                            + ". Run `zolt resolve --workspace` to create it, then retry `zolt resolve --workspace --locked`.");
        }
        options = lockfilePersistence.prepare(lockfilePath, options)
                .withWorkspaceMemberCoordinates(workspaceMemberCoordinates(workspace));

        Map<String, WorkspaceMember> membersByPath = membersByPath(workspace);
        Map<String, ProjectConfig> effectiveConfigs = new LinkedHashMap<>();
        List<WorkspaceMemberResolveOutput> memberOutputs = new ArrayList<>();
        int downloadCount = 0;
        ResolveMetrics metrics = ResolveMetrics.empty();
        for (String memberPath : workspace.buildOrder()) {
            WorkspaceMember member = membersByPath.get(memberPath);
            ProjectConfig effectiveConfig = policyMerger.merge(workspace, member);
            effectiveConfigs.put(member.path(), effectiveConfig);
            ResolveOutput output = resolveService.resolveLockfile(
                    effectiveConfig,
                    cacheRoot,
                    options);
            memberOutputs.add(WorkspaceMemberResolveOutputFacts.of(
                    member.path(), effectiveConfig, output));
            downloadCount += output.downloadCount();
            metrics = metrics.plus(output.metrics());
        }

        List<LockConflict> initialMemberConflicts =
                memberConflicts(memberOutputs);
        WorkspaceMediationResult mediation =
                new WorkspaceMediationFixedPoint(resolveService).mediate(
                        workspace,
                        memberOutputs,
                        membersByPath,
                        effectiveConfigs,
                        cacheRoot,
                        options);
        memberOutputs = mediation.memberOutputs();
        downloadCount += mediation.downloadCount();
        metrics = metrics.plus(mediation.metrics());
        WorkspaceProvidedArtifactMediator provided =
                new WorkspaceProvidedArtifactMediator(workspace);
        List<LockPackage> shadowCandidates =
                provided.policyCandidates(memberOutputs);
        List<LockConflict> shadowConflicts =
                provided.conflicts(memberOutputs);
        WorkspaceMediationPolicyEnforcer.enforce(
                shadowCandidates,
                shadowConflicts,
                provided.selectedVersions(),
                effectiveConfigs,
                options.retryCommand());
        List<LockPolicyEffect> shadowPolicyEffects =
                WorkspaceMediationPolicyEffects.from(
                        shadowCandidates,
                        provided.selectedVersions());

        ZoltLockfile lockfile =
                lockfileAggregator.aggregate(
                        workspace,
                        memberOutputs,
                        merged(
                                initialMemberConflicts,
                                merged(
                                        mediation.conflicts(),
                                        shadowConflicts)),
                        merged(mediation.policyEffects(), shadowPolicyEffects));
        if (!locked) {
            workspace.inputs().requireCurrent();
            lockfile = lockfile.withWorkspaceResolutionInputFingerprint(recordedFingerprint(
                    workspace, effectiveConfigs, lockfile));
        }
        long started = System.nanoTime();
        WorkspaceResolveLockfilePersistence.CommittedLockfile committed =
                lockfilePersistence.persist(
                        lockfilePath,
                        lockfile,
                        locked);
        if (locked) {
            metrics = metrics.withLockfileVerificationNanos(elapsedSince(started));
        } else {
            metrics = metrics.withLockfileWriteNanos(elapsedSince(started));
        }
        ResolveResult result = new ResolveResult(
                lockfile.packages().size(),
                downloadCount,
                lockfile.conflicts().size(),
                lockfilePath,
                metrics);
        return new WorkspaceResolveSnapshot(
                result,
                committed.bytes(),
                committed.lockfile());
    }

    /**
     * The fingerprint certifies the lock as well as the inputs, so it is computed over the lock this
     * resolve is about to commit. Rendering the candidate first is safe and stable: the fingerprint
     * line is excluded from the canonical content the digest covers, so the value cannot depend on
     * itself and a repeated resolve of unchanged inputs writes identical bytes.
     */
    private Optional<String> recordedFingerprint(
            Workspace workspace,
            Map<String, ProjectConfig> effectiveConfigs,
            ZoltLockfile candidate) {
        return WorkspaceResolutionInputFingerprint.fingerprint(
                workspace, effectiveConfigs, lockfileWriter.write(candidate));
    }

    private static long elapsedSince(long started) {
        return Math.max(0L, System.nanoTime() - started);
    }

    private static Set<PackageId> workspaceMemberCoordinates(Workspace workspace) {
        Set<PackageId> coordinates = new LinkedHashSet<>();
        for (WorkspaceMember member : workspace.members()) {
            coordinates.add(new PackageId(
                    member.config().project().group(),
                    member.config().project().name()));
        }
        return Set.copyOf(coordinates);
    }

    private static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }

    private static <T> List<T> merged(
            List<T> first,
            List<T> second) {
        Set<T> values = new LinkedHashSet<>(first);
        values.addAll(second);
        return List.copyOf(values);
    }

    private static List<LockConflict> memberConflicts(
            List<WorkspaceMemberResolveOutput> outputs) {
        List<LockConflict> conflicts = new ArrayList<>();
        for (WorkspaceMemberResolveOutput output : outputs) {
            for (LockConflict conflict : output.lockfile().conflicts()) {
                Set<String> members =
                        new LinkedHashSet<>(conflict.members());
                members.add(output.member());
                conflicts.add(new LockConflict(
                        conflict.packageId(),
                        conflict.selectedVersion(),
                        conflict.requestedVersions(),
                        conflict.reason(),
                        conflict.toolGroup(),
                        conflict.variant(),
                        members.stream().sorted().toList()));
            }
        }
        return List.copyOf(conflicts);
    }

}
