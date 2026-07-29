package sh.zolt.workspace.service;

import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class WorkspaceExecutionContext {
    private final Workspace workspace;
    private final ZoltLockfile lockfile;
    private final Path cacheRoot;
    private final WorkspaceClasspathMemberGraph memberGraph;
    private final WorkspaceFileSnapshot fileSnapshot;
    private final WorkspaceAbiIndex abiIndex;
    private final WorkspaceToolchainIndex toolchainIndex;
    private final long graphConstructionNanos;
    private final Map<ClasspathKey, ClasspathSet> classpaths = new LinkedHashMap<>();
    private final Map<String, List<ResolvedClasspathPackage>> classpathPackages = new LinkedHashMap<>();
    private final Map<String, ZoltLockfile> packageLocks = new LinkedHashMap<>();
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

    synchronized ClasspathSet classpaths(
            String member,
            WorkspaceBuildRequirements requirements,
            Supplier<ClasspathSet> calculation) {
        ClasspathKey key = new ClasspathKey(member, requirements);
        ClasspathSet cached = classpaths.get(key);
        if (cached != null) {
            classpathCacheHits++;
            return cached;
        }
        long started = System.nanoTime();
        ClasspathSet value = calculation.get();
        classpathCalculationNanos += elapsedSince(started);
        classpaths.put(key, value);
        return value;
    }

    synchronized List<ResolvedClasspathPackage> classpathPackages(
            String member,
            Supplier<List<ResolvedClasspathPackage>> calculation) {
        List<ResolvedClasspathPackage> cached = classpathPackages.get(member);
        if (cached != null) {
            packageCacheHits++;
            return cached;
        }
        long started = System.nanoTime();
        List<ResolvedClasspathPackage> value = List.copyOf(calculation.get());
        packageCalculationNanos += elapsedSince(started);
        classpathPackages.put(member, value);
        return value;
    }

    synchronized ZoltLockfile packageLock(
            String member,
            Supplier<ZoltLockfile> calculation) {
        ZoltLockfile cached = packageLocks.get(member);
        if (cached != null) {
            packageCacheHits++;
            return cached;
        }
        long started = System.nanoTime();
        ZoltLockfile value = calculation.get();
        packageCalculationNanos += elapsedSince(started);
        packageLocks.put(member, value);
        return value;
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
                abiIndex.reads(),
                abiIndex.hits(),
                toolchainIndex.resolutions(),
                toolchainIndex.hits());
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

    synchronized void addDirtyPlanMetrics(
            int considered,
            int pipelineInvocations) {
        membersConsidered += Math.max(0, considered);
        memberPipelineInvocations += Math.max(0, pipelineInvocations);
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
            int abiStateReads,
            int abiStateCacheHits,
            int toolchainResolutions,
            int toolchainCacheHits) {
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
                    0);
        }

        public Metrics(
                long graphConstructionNanos,
                long classpathCalculationNanos,
                long packageCalculationNanos,
                long memberExecutionNanos,
                long schedulerIdleNanos,
                int classpathCalculations,
                int packageCalculations,
                int classpathCacheHits,
                int packageCacheHits,
                int readyQueuePeak) {
            this(
                    graphConstructionNanos,
                    classpathCalculationNanos,
                    packageCalculationNanos,
                    memberExecutionNanos,
                    schedulerIdleNanos,
                    classpathCalculations,
                    packageCalculations,
                    classpathCacheHits,
                    packageCacheHits,
                    readyQueuePeak,
                    0L,
                    0L,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0);
        }

        public Metrics(
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
                int memberPipelineInvocations) {
            this(
                    graphConstructionNanos,
                    classpathCalculationNanos,
                    packageCalculationNanos,
                    memberExecutionNanos,
                    schedulerIdleNanos,
                    classpathCalculations,
                    packageCalculations,
                    classpathCacheHits,
                    packageCacheHits,
                    readyQueuePeak,
                    fileSnapshotNanos,
                    bytesHashed,
                    filesHashed,
                    membersConsidered,
                    membersDeclaredClean,
                    memberPipelineInvocations,
                    0,
                    0,
                    0,
                    0);
        }
    }
}
