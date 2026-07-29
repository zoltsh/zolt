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
    private final long graphConstructionNanos;
    private final Map<ClasspathKey, ClasspathSet> classpaths = new LinkedHashMap<>();
    private final Map<String, List<ResolvedClasspathPackage>> classpathPackages = new LinkedHashMap<>();
    private final Map<String, ZoltLockfile> packageLocks = new LinkedHashMap<>();
    private long classpathCalculationNanos;
    private long packageCalculationNanos;
    private long memberExecutionNanos;
    private int classpathCacheHits;
    private int packageCacheHits;

    public WorkspaceExecutionContext(
            Workspace workspace,
            ZoltLockfile lockfile,
            Path cacheRoot) {
        this.workspace = workspace;
        this.lockfile = lockfile;
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        long started = System.nanoTime();
        this.memberGraph = new WorkspaceClasspathMemberGraph(workspace);
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
                classpaths.size(),
                classpathPackages.size() + packageLocks.size(),
                classpathCacheHits,
                packageCacheHits);
    }

    synchronized void addMemberExecutionNanos(long durationNanos) {
        memberExecutionNanos += Math.max(0L, durationNanos);
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
            int classpathCalculations,
            int packageCalculations,
            int classpathCacheHits,
            int packageCacheHits) {
    }
}
