package sh.zolt.workspace.service;

import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.workspace.resolve.WorkspaceMemberLaneClosure;
import sh.zolt.workspace.state.WorkspaceFileSnapshot;
import sh.zolt.workspace.state.WorkspaceParanoidMode;
import sh.zolt.workspace.state.WorkspaceState;
import sh.zolt.workspace.state.WorkspaceStateStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class WorkspaceExecutionContext {
    private final Workspace workspace;
    private final ZoltLockfile lockfile;
    private final Path cacheRoot;
    private final WorkspaceClasspathMemberGraph memberGraph;
    private final WorkspaceAbiIndex abiIndex;
    private final WorkspaceToolchainIndex toolchainIndex;
    private final WorkspaceLockIndex lockIndex;
    private final boolean paranoid = WorkspaceParanoidMode.enabled();
    private WorkspaceState previousState;
    private WorkspaceFileSnapshot fileSnapshot;
    private WorkspaceMemberLaneClosure laneClosure;
    /**
     * Scoped to this context, and therefore to the command that created it: every lock projection
     * the command makes verifies an artifact through the same index, so each file is hashed once,
     * while a later command starts empty and re-reads anything modified in between.
     */
    private final VerifiedArtifactIndex artifactIndex;
    private final long graphConstructionNanos;
    private final Map<ClasspathKey, ClasspathSet> classpaths =
            new ConcurrentHashMap<>();
    private final Map<String, List<ResolvedClasspathPackage>> classpathPackages =
            new ConcurrentHashMap<>();
    private final Map<String, ZoltLockfile> packageLocks =
            new ConcurrentHashMap<>();
    private final WorkspaceExecutionCounters counters = new WorkspaceExecutionCounters();

    public WorkspaceExecutionContext(
            Workspace workspace,
            ZoltLockfile lockfile,
            Path cacheRoot) {
        this(workspace, lockfile, cacheRoot, new VerifiedArtifactIndex());
    }

    public WorkspaceExecutionContext(
            Workspace workspace,
            ZoltLockfile lockfile,
            Path cacheRoot,
            VerifiedArtifactIndex artifactIndex) {
        this.workspace = workspace;
        this.lockfile = lockfile;
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        this.artifactIndex = artifactIndex == null ? new VerifiedArtifactIndex() : artifactIndex;
        long started = System.nanoTime();
        this.memberGraph = new WorkspaceClasspathMemberGraph(workspace);
        this.abiIndex = new WorkspaceAbiIndex();
        this.toolchainIndex = new WorkspaceToolchainIndex();
        this.lockIndex = new WorkspaceLockIndex(lockfile);
        this.graphConstructionNanos = elapsedSince(started);
    }

    public Workspace workspace() {
        return workspace;
    }
    public ZoltLockfile lockfile() {
        return lockfile;
    }
    public Path cacheRoot() {
        return cacheRoot;
    }
    WorkspaceClasspathMemberGraph memberGraph() {
        return memberGraph;
    }

    /**
     * The state the previous command committed, read once per command. Stage 0 decides dirtiness
     * from its member rows and the file snapshot reuses its file rows, so both see one consistent
     * read of one file rather than two reads that could straddle a concurrent write.
     */
    synchronized WorkspaceState previousState() {
        if (previousState == null) {
            previousState = new WorkspaceStateStore().read(workspace.root());
        }
        return previousState;
    }

    /**
     * The one file snapshot this command hashes through, built behind the previous command's file
     * table so an unchanged input is statted rather than read.
     */
    public synchronized WorkspaceFileSnapshot fileSnapshot() {
        if (fileSnapshot == null) {
            fileSnapshot = new WorkspaceFileSnapshot(
                    workspace.root(),
                    previousState().files(),
                    paranoid);
        }
        return fileSnapshot;
    }
    WorkspaceAbiIndex abiIndex() {
        return abiIndex;
    }
    WorkspaceToolchainIndex toolchainIndex() {
        return toolchainIndex;
    }
    WorkspaceLockIndex lockIndex() {
        return lockIndex;
    }

    /**
     * The one lane closure this command uses. Stage 0 asks it whether a member's lanes moved and
     * stage 1 asks it what to project onto them, so both read the same answer and a lane computed for
     * either is already cached for the other.
     */
    synchronized WorkspaceMemberLaneClosure laneClosure() {
        if (laneClosure == null) {
            laneClosure = new WorkspaceMemberLaneClosure(
                    lockfile,
                    lockIndex.dependencies(),
                    lockIndex.memberGraphs(),
                    memberGraph);
        }
        return laneClosure;
    }
    public VerifiedArtifactIndex artifactIndex() {
        return artifactIndex;
    }

    ClasspathSet classpaths(
            String member,
            WorkspaceBuildRequirements requirements,
            Supplier<ClasspathSet> calculation) {
        ClasspathKey key = new ClasspathKey(
                member,
                requirements.withPackageInputs(false));
        ClasspathSet cached = classpaths.get(key);
        if (cached != null) {
            counters.addClasspathCacheHit();
            return cached;
        }
        long started = System.nanoTime();
        ClasspathSet value = calculation.get();
        ClasspathSet existing = classpaths.putIfAbsent(key, value);
        if (existing != null) {
            counters.addClasspathCacheHit();
            return existing;
        }
        counters.addClasspathCalculation(elapsedSince(started), requirements);
        return value;
    }

    List<ResolvedClasspathPackage> classpathPackages(
            String member,
            Supplier<List<ResolvedClasspathPackage>> calculation) {
        List<ResolvedClasspathPackage> cached = classpathPackages.get(member);
        if (cached != null) {
            counters.addPackageCacheHit();
            return cached;
        }
        long started = System.nanoTime();
        List<ResolvedClasspathPackage> value = List.copyOf(calculation.get());
        List<ResolvedClasspathPackage> existing =
                classpathPackages.putIfAbsent(member, value);
        if (existing != null) {
            counters.addPackageCacheHit();
            return existing;
        }
        counters.addPackageCalculation(elapsedSince(started));
        return value;
    }

    ZoltLockfile packageLock(
            String member,
            Supplier<ZoltLockfile> calculation) {
        ZoltLockfile cached = packageLocks.get(member);
        if (cached != null) {
            counters.addPackageCacheHit();
            return cached;
        }
        long started = System.nanoTime();
        ZoltLockfile value = calculation.get();
        ZoltLockfile existing = packageLocks.putIfAbsent(member, value);
        if (existing != null) {
            counters.addPackageCacheHit();
            return existing;
        }
        counters.addPackageCalculation(elapsedSince(started));
        return value;
    }

    public Metrics metrics() {
        return counters.metrics(
                graphConstructionNanos,
                classpaths.size(),
                classpathPackages.size() + packageLocks.size(),
                abiIndex,
                toolchainIndex,
                artifactIndex);
    }

    void addMemberExecutionNanos(long durationNanos) {
        counters.addMemberExecutionNanos(durationNanos);
    }

    void addSchedulerMetrics(long idleNanos, int queuePeak) {
        counters.addSchedulerMetrics(idleNanos, queuePeak);
    }

    void addFileSnapshotMetrics(long durationNanos, WorkspaceFileSnapshot snapshot) {
        counters.addFileSnapshotMetrics(durationNanos, snapshot);
    }

    void addDirtyPlanMetrics(int considered, int admitted, int pipelineInvocations, int finalized) {
        counters.addDirtyPlanMetrics(considered, admitted, pipelineInvocations, finalized);
    }

    private static long elapsedSince(long started) {
        return Math.max(0L, System.nanoTime() - started);
    }

    private record ClasspathKey(
            String member,
            WorkspaceBuildRequirements requirements) {
    }

    public record Metrics(
            long graphConstructionNanos,
            long classpathCalculationNanos,
            long packageCalculationNanos,
            long memberExecutionNanos,
            long schedulerIdleNanos,
            int classpathCalculations,
            int packageCalculations,
            int classpathCacheHits,
            int packageCacheHits,
            int readyQueuePeak,
            long fileSnapshotNanos,
            long bytesHashed,
            int filesHashed,
            int filesStatted,
            int filesReused,
            int membersConsidered,
            int membersDeclaredClean,
            int memberPipelineInvocations,
            int membersAdmitted,
            int membersFinalized,
            int runtimeClasspathCalculations,
            int testClasspathCalculations,
            int abiStateReads,
            int abiStateCacheHits,
            int toolchainResolutions,
            int toolchainCacheHits,
            int toolchainLockfileParses,
            int toolchainIdentityCalculations,
            int toolchainIdentityCacheHits,
            VerifiedArtifactIndex.Metrics artifactIntegrity) {
        public Metrics(
                long graphConstructionNanos,
                long classpathCalculationNanos,
                long packageCalculationNanos,
                long memberExecutionNanos,
                int classpathCalculations,
                int packageCalculations,
                int classpathCacheHits,
                int packageCacheHits) {
            this(
                    graphConstructionNanos,
                    classpathCalculationNanos,
                    packageCalculationNanos,
                    memberExecutionNanos,
                    0L,
                    classpathCalculations,
                    packageCalculations,
                    classpathCacheHits,
                    packageCacheHits,
                    0,
                    0L,
                    0L,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    VerifiedArtifactIndex.Metrics.empty());
        }

    }
}
