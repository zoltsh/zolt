package sh.zolt.workspace.service;

import sh.zolt.build.BuildResult;
import sh.zolt.build.CompileDiagnostics;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.resolve.ResolveResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * @param membersRequiringTestCompile members whose test classes are not known to be current, from
 *     the same stage-0 evidence the main lane used. {@code null} means "every member", which is
 *     what a result built outside the workspace build path must assume.
 */
public record WorkspaceBuildResult(
        Optional<ResolveResult> resolveResult,
        List<MemberBuildResult> members,
        int buildWaveCount,
        int buildMaxWorkers,
        WorkspaceExecutionContext.Metrics executionMetrics,
        Set<String> membersRequiringTestCompile) {
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

    public WorkspaceBuildResult(
            Optional<ResolveResult> resolveResult,
            List<MemberBuildResult> members,
            int buildWaveCount,
            int buildMaxWorkers,
            WorkspaceExecutionContext.Metrics executionMetrics) {
        this(resolveResult, members, buildWaveCount, buildMaxWorkers, executionMetrics, null);
    }

    public WorkspaceBuildResult {
        resolveResult = resolveResult == null ? Optional.empty() : resolveResult;
        members = List.copyOf(members);
        buildWaveCount = Math.max(0, buildWaveCount);
        buildMaxWorkers = Math.max(0, buildMaxWorkers);
        executionMetrics = executionMetrics == null ? emptyMetrics() : executionMetrics;
        membersRequiringTestCompile = membersRequiringTestCompile == null
                ? everyMember(members)
                : Set.copyOf(membersRequiringTestCompile);
    }

    private static Set<String> everyMember(List<MemberBuildResult> members) {
        Set<String> names = new LinkedHashSet<>();
        members.forEach(member -> names.add(member.member()));
        return Set.copyOf(names);
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

    /**
     * A member's build outcome, with its classpaths reachable but not necessarily built.
     *
     * <p>A workspace build no longer projects a classpath for a member it never admitted, so the
     * classpaths here are produced on first access. A consumer that needs them — a test lane, a run
     * launch, a package assembly — gets exactly the same set it always did; a consumer that only
     * reads the build outcome costs nothing. Access is memoized, so asking twice builds once.
     */
    public static final class MemberBuildResult {
        private final String member;
        private final BuildResult result;
        private final Supplier<ClasspathSet> classpaths;
        private final Supplier<List<ResolvedClasspathPackage>> classpathPackages;

        public MemberBuildResult(
                String member,
                BuildResult result,
                ClasspathSet classpaths,
                List<ResolvedClasspathPackage> classpathPackages) {
            List<ResolvedClasspathPackage> packages = List.copyOf(classpathPackages);
            this.member = member;
            this.result = result;
            this.classpaths = () -> classpaths;
            this.classpathPackages = () -> packages;
        }

        MemberBuildResult(
                String member,
                BuildResult result,
                Supplier<ClasspathSet> classpaths,
                Supplier<List<ResolvedClasspathPackage>> classpathPackages) {
            this.member = member;
            this.result = result;
            this.classpaths = memoized(classpaths);
            this.classpathPackages = memoized(classpathPackages);
        }

        public String member() {
            return member;
        }

        public BuildResult result() {
            return result;
        }

        public ClasspathSet classpaths() {
            return classpaths.get();
        }

        public List<ResolvedClasspathPackage> classpathPackages() {
            return classpathPackages.get();
        }

        @Override
        public String toString() {
            return "MemberBuildResult[member=" + member + ", result=" + result + "]";
        }

        private static <T> Supplier<T> memoized(Supplier<T> supplier) {
            return new Supplier<>() {
                private volatile T value;

                @Override
                public T get() {
                    T current = value;
                    if (current == null) {
                        synchronized (this) {
                            if (value == null) {
                                value = supplier.get();
                            }
                            current = value;
                        }
                    }
                    return current;
                }
            };
        }
    }
}
