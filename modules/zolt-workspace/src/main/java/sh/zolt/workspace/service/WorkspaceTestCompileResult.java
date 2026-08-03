package sh.zolt.workspace.service;

import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.resolve.ResolveResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public record WorkspaceTestCompileResult(
        Optional<ResolveResult> resolveResult,
        List<WorkspaceBuildResult.MemberBuildResult> builtMembers,
        List<MemberTestCompileResult> members,
        int totalMemberCount,
        int maxWorkers,
        WorkspaceTestToolchainMetrics toolchainMetrics) {
    public WorkspaceTestCompileResult {
        resolveResult = resolveResult == null ? Optional.empty() : resolveResult;
        builtMembers = List.copyOf(builtMembers);
        members = List.copyOf(members);
        totalMemberCount = Math.max(totalMemberCount, members.size());
        maxWorkers = Math.max(0, maxWorkers);
        toolchainMetrics = toolchainMetrics == null
                ? WorkspaceTestToolchainMetrics.empty()
                : toolchainMetrics;
    }

    public WorkspaceTestCompileResult(
            Optional<ResolveResult> resolveResult,
            List<WorkspaceBuildResult.MemberBuildResult> builtMembers,
            List<MemberTestCompileResult> members,
            int totalMemberCount,
            int maxWorkers) {
        this(
                resolveResult,
                builtMembers,
                members,
                totalMemberCount,
                maxWorkers,
                WorkspaceTestToolchainMetrics.empty());
    }

    public boolean resolvedLockfile() {
        return resolveResult.isPresent();
    }

    public int mainSourceCount() {
        return builtMembers.stream()
                .map(WorkspaceBuildResult.MemberBuildResult::result)
                .mapToInt(result -> result.sourceCount())
                .sum();
    }

    public int includedMemberCount() {
        return builtMembers.size();
    }

    public int selectedMemberCount() {
        return members.size();
    }

    public int dependencyMemberCount() {
        Set<String> selected = new LinkedHashSet<>(members.stream()
                .map(MemberTestCompileResult::member)
                .toList());
        return (int) builtMembers.stream()
                .map(WorkspaceBuildResult.MemberBuildResult::member)
                .filter(member -> !selected.contains(member))
                .count();
    }

    public int testSourceCount() {
        return members.stream()
                .map(MemberTestCompileResult::result)
                .mapToInt(TestCompileResult::sourceCount)
                .sum();
    }

    public int mainCompilationSkippedCount() {
        return (int) builtMembers.stream()
                .map(WorkspaceBuildResult.MemberBuildResult::result)
                .filter(result -> result.mainCompilationSkipped())
                .count();
    }

    public int mainCompilationExecutedCount() {
        return builtMembers.size() - mainCompilationSkippedCount();
    }

    public int testCompilationSkippedCount() {
        return (int) members.stream()
                .map(MemberTestCompileResult::result)
                .filter(TestCompileResult::testCompilationSkipped)
                .count();
    }

    public int testCompilationExecutedCount() {
        return members.size() - testCompilationSkippedCount();
    }

    public long mainFingerprintCheckNanos() {
        return builtMembers.stream()
                .map(WorkspaceBuildResult.MemberBuildResult::result)
                .mapToLong(result -> result.mainFingerprintCheckNanos())
                .sum();
    }

    public long mainFingerprintWriteNanos() {
        return builtMembers.stream()
                .map(WorkspaceBuildResult.MemberBuildResult::result)
                .mapToLong(result -> result.mainFingerprintWriteNanos())
                .sum();
    }

    public long testFingerprintCheckNanos() {
        return members.stream()
                .map(MemberTestCompileResult::result)
                .mapToLong(TestCompileResult::testFingerprintCheckNanos)
                .sum();
    }

    public long testFingerprintWriteNanos() {
        return members.stream()
                .map(MemberTestCompileResult::result)
                .mapToLong(TestCompileResult::testFingerprintWriteNanos)
                .sum();
    }

    public record MemberTestCompileResult(
            String member,
            TestCompileResult result) {
    }
}
