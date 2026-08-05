package sh.zolt.workspace.service;

import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.workspace.resolve.WorkspaceMemberLaneClosure;
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
    private final WorkspaceFileSnapshot fileSnapshot;
    private final WorkspaceAbiIndex abiIndex;
    private final WorkspaceToolchainIndex toolchainIndex;
    private final WorkspaceLockIndex lockIndex;
    private WorkspaceMemberLaneClosure laneClosure;
    /**
     * Scoped to this context, and therefore to the command that created it: every lock projection
     * the command makes verifies an artifact through the same index, so each file is hashed once,
     * while a later command starts empty and re-reads anything modified in between.
     */
    private final VerifiedArtifactIndex artifactIndex = new VerifiedArtifactIndex();
    private final long graphConstructionNanos;
    private final Map<ClasspathKey, ClasspathSet> classpaths =
            new ConcurrentHashMap<>();
    private final Map<String, List<ResolvedClasspathPackage>> classpathPackages =
            new ConcurrentHashMap<>();
    private final Map<String, ZoltLockfile> packageLocks =
            new ConcurrentHashMap<>();
    private long classpathCalculationNanos;
    private long packageCalculationNanos;
    private long memberExecutionNanos;
    private long schedulerIdleNanos;
    private long fileSnapshotNanos;
    private long bytesHashed;
    private int classpathCacheHits;
    private int packageCacheHits;
    private int readyQueuePeak;
    private int filesHashed;
    private int membersConsidered;
    private int membersDeclaredClean;
    private int memberPipelineInvocations;
    private int membersAdmitted;
    private int membersFinalized;
    private int runtimeClasspathCalculations;
    private int testClasspathCalculations;

    public WorkspaceExecutionContext(
            Workspace workspace,
            ZoltLockfile lockfile,
            Path cacheRoot) {
        this.workspace = workspace;
        this.lockfile = lockfile;
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        long started = System.nanoTime();
        this.memberGraph = new WorkspaceClasspathMemberGraph(workspace);
        this.fileSnapshot = new WorkspaceFileSnapshot();
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
    WorkspaceFileSnapshot fileSnapshot() {
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
            recordClasspathCacheHit();
            return cached;
        }
        long started = System.nanoTime();
        ClasspathSet value = calculation.get();
        ClasspathSet existing = classpaths.putIfAbsent(key, value);
        if (existing != null) {
            recordClasspathCacheHit();
            return existing;
        }
        recordClasspathCalculation(elapsedSince(started), requirements);
        return value;
    }

    List<ResolvedClasspathPackage> classpathPackages(
            String member,
            Supplier<List<ResolvedClasspathPackage>> calculation) {
        List<ResolvedClasspathPackage> cached = classpathPackages.get(member);
        if (cached != null) {
            recordPackageCacheHit();
            return cached;
        }
        long started = System.nanoTime();
        List<ResolvedClasspathPackage> value = List.copyOf(calculation.get());
        List<ResolvedClasspathPackage> existing =
                classpathPackages.putIfAbsent(member, value);
        if (existing != null) {
            recordPackageCacheHit();
            return existing;
        }
        recordPackageCalculation(elapsedSince(started));
        return value;
    }

    ZoltLockfile packageLock(
            String member,
            Supplier<ZoltLockfile> calculation) {
        ZoltLockfile cached = packageLocks.get(member);
        if (cached != null) {
            recordPackageCacheHit();
            return cached;
        }
        long started = System.nanoTime();
        ZoltLockfile value = calculation.get();
        ZoltLockfile existing = packageLocks.putIfAbsent(member, value);
        if (existing != null) {
            recordPackageCacheHit();
            return existing;
        }
        recordPackageCalculation(elapsedSince(started));
        return value;
    }

    private synchronized void recordClasspathCalculation(
            long durationNanos,
            WorkspaceBuildRequirements requirements) {
        classpathCalculationNanos += durationNanos;
        if (requirements.mainRuntimeClasspath()) {
            runtimeClasspathCalculations++;
        }
        if (requirements.testCompileClasspath()) {
            testClasspathCalculations++;
        }
    }

    private synchronized void recordPackageCalculation(long durationNanos) {
        packageCalculationNanos += durationNanos;
    }

    private synchronized void recordClasspathCacheHit() {
        classpathCacheHits++;
    }

    private synchronized void recordPackageCacheHit() {
        packageCacheHits++;
    }

    public synchronized Metrics metrics() {
        return new Metrics(
                graphConstructionNanos,
                classpathCalculationNanos,
                packageCalculationNanos,
                memberExecutionNanos,
                schedulerIdleNanos,
                classpaths.size(),
                classpathPackages.size() + packageLocks.size(),
                classpathCacheHits,
                packageCacheHits,
                readyQueuePeak,
                fileSnapshotNanos,
                bytesHashed,
                filesHashed,
                membersConsidered,
                membersDeclaredClean,
                memberPipelineInvocations,
                membersAdmitted,
                membersFinalized,
                runtimeClasspathCalculations,
                testClasspathCalculations,
                abiIndex.reads(),
                abiIndex.hits(),
                toolchainIndex.resolutions(),
                toolchainIndex.hits(),
                toolchainIndex.lockfileParses(),
                toolchainIndex.identityCalculations(),
                toolchainIndex.identityHits(),
                artifactIndex.metrics());
    }

    synchronized void addMemberExecutionNanos(long durationNanos) {
        memberExecutionNanos += Math.max(0L, durationNanos);
    }

    synchronized void addSchedulerMetrics(long idleNanos, int queuePeak) {
        schedulerIdleNanos += Math.max(0L, idleNanos);
        readyQueuePeak = Math.max(readyQueuePeak, queuePeak);
    }

    synchronized void addFileSnapshotMetrics(
            long durationNanos,
            long hashedBytes,
            int hashedFiles) {
        fileSnapshotNanos += Math.max(0L, durationNanos);
        bytesHashed = Math.max(bytesHashed, hashedBytes);
        filesHashed = Math.max(filesHashed, hashedFiles);
    }

    /**
     * {@code admitted} is how many members the scheduler let into the executor at all — the number
     * that could cost a classpath. {@code pipelineInvocations} is the subset that ran the canonical
     * member build; {@code finalized} is the subset that only had its clean outputs assured.
     */
    synchronized void addDirtyPlanMetrics(
            int considered,
            int admitted,
            int pipelineInvocations,
            int finalized) {
        membersConsidered += Math.max(0, considered);
        membersAdmitted += Math.max(0, admitted);
        memberPipelineInvocations += Math.max(0, pipelineInvocations);
        membersFinalized += Math.max(0, finalized);
        membersDeclaredClean += Math.max(0, considered - pipelineInvocations);
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
                    VerifiedArtifactIndex.Metrics.empty());
        }

    }
}
