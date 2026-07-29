package sh.zolt.workspace.service;

import sh.zolt.build.BuildResult;
import sh.zolt.build.CompileDiagnostics;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.resolve.ResolveResult;
import java.util.List;
import java.util.Optional;

public record WorkspaceBuildResult(
        Optional<ResolveResult> resolveResult,
        List<MemberBuildResult> members,
        int buildWaveCount,
        int buildMaxWorkers,
        WorkspaceExecutionContext.Metrics executionMetrics) {
    public WorkspaceBuildResult(
            Optional<ResolveResult> resolveResult,
            List<MemberBuildResult> members) {
        this(
                resolveResult,
                members,
                defaultWaveCount(members),
                defaultMaxWorkers(members),
                emptyMetrics());
    }

    public WorkspaceBuildResult(
            Optional<ResolveResult> resolveResult,
            List<MemberBuildResult> members,
            int buildWaveCount,
            int buildMaxWorkers) {
        this(resolveResult, members, buildWaveCount, buildMaxWorkers, emptyMetrics());
    }

    public WorkspaceBuildResult {
        resolveResult = resolveResult == null ? Optional.empty() : resolveResult;
        members = List.copyOf(members);
        buildWaveCount = Math.max(0, buildWaveCount);
        buildMaxWorkers = Math.max(0, buildMaxWorkers);
        executionMetrics = executionMetrics == null ? emptyMetrics() : executionMetrics;
    }

    public boolean resolvedLockfile() {
        return resolveResult.isPresent();
    }

    public int sourceCount() {
        return members.stream()
                .map(MemberBuildResult::result)
                .mapToInt(BuildResult::sourceCount)
                .sum();
    }

    public int compiledSourceCount() {
        return members.stream()
                .map(MemberBuildResult::result)
                .filter(result -> !result.mainCompilationSkipped())
                .mapToInt(BuildResult::sourceCount)
                .sum();
    }

    public int mainCompilationSkippedCount() {
        return (int) members.stream()
                .map(MemberBuildResult::result)
                .filter(BuildResult::mainCompilationSkipped)
                .count();
    }

    public int mainCompilationExecutedCount() {
        return members.size() - mainCompilationSkippedCount();
    }

    public long mainFingerprintCheckNanos() {
        return members.stream()
                .map(MemberBuildResult::result)
                .mapToLong(BuildResult::mainFingerprintCheckNanos)
                .sum();
    }

    public long mainFingerprintWriteNanos() {
        return members.stream()
                .map(MemberBuildResult::result)
                .mapToLong(BuildResult::mainFingerprintWriteNanos)
                .sum();
    }

    public long mainFingerprintCheckMillis() {
        return mainFingerprintCheckNanos() / 1_000_000L;
    }

    public long mainFingerprintWriteMillis() {
        return mainFingerprintWriteNanos() / 1_000_000L;
    }

    public CompileDiagnostics mainCompileDiagnostics() {
        return new CompileDiagnostics(
                sumDiagnostics(CompileDiagnostics::sourcesAdded),
                sumDiagnostics(CompileDiagnostics::sourcesChanged),
                sumDiagnostics(CompileDiagnostics::sourcesDeleted),
                sumDiagnostics(CompileDiagnostics::sourcesRecompiled),
                sumDiagnostics(CompileDiagnostics::dependentSourcesRecompiled),
                sumDiagnostics(CompileDiagnostics::classesDeleted),
                sumDiagnostics(CompileDiagnostics::abiChangedClasses),
                sumDiagnostics(CompileDiagnostics::packagePrivateAbiChangedClasses));
    }

    public int workspaceAbiInvalidationCount() {
        return (int) members.stream()
                .map(MemberBuildResult::result)
                .filter(result -> "compile-classpath-changed".equals(result.mainIncrementalFallbackReason()))
                .count();
    }

    private int sumDiagnostics(java.util.function.ToIntFunction<CompileDiagnostics> value) {
        return members.stream()
                .map(MemberBuildResult::result)
                .map(BuildResult::mainCompileDiagnostics)
                .mapToInt(value)
                .sum();
    }

    private static int defaultWaveCount(List<MemberBuildResult> members) {
        return members == null || members.isEmpty() ? 0 : 1;
    }

    private static int defaultMaxWorkers(List<MemberBuildResult> members) {
        return members == null || members.isEmpty() ? 0 : 1;
    }

    private static WorkspaceExecutionContext.Metrics emptyMetrics() {
        return new WorkspaceExecutionContext.Metrics(0L, 0L, 0L, 0L, 0, 0, 0, 0);
    }

    public record MemberBuildResult(
            String member,
            BuildResult result,
            ClasspathSet classpaths,
            List<ResolvedClasspathPackage> classpathPackages) {
        public MemberBuildResult {
            classpathPackages = List.copyOf(classpathPackages);
        }
    }
}
