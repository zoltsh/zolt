package sh.zolt.workspace.service;

import sh.zolt.build.testruntime.TestRunResult;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.workspace.testpool.WorkspaceTestPoolMetrics;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public record WorkspaceTestResult(
        Optional<ResolveResult> resolveResult,
        List<WorkspaceBuildResult.MemberBuildResult> builtMembers,
        List<MemberTestRunResult> members,
        int totalMemberCount,
        Optional<Path> profileDirectory,
        WorkspaceTestToolchainMetrics toolchainMetrics,
        WorkspaceTestPoolMetrics poolMetrics) {
    public WorkspaceTestResult {
        resolveResult = resolveResult == null ? Optional.empty() : resolveResult;
        builtMembers = List.copyOf(builtMembers);
        members = List.copyOf(members);
        totalMemberCount = Math.max(totalMemberCount, members.size());
        profileDirectory = profileDirectory == null ? Optional.empty() : profileDirectory;
        toolchainMetrics = toolchainMetrics == null
                ? WorkspaceTestToolchainMetrics.empty()
                : toolchainMetrics;
        poolMetrics = poolMetrics == null ? WorkspaceTestPoolMetrics.empty() : poolMetrics;
    }

    public WorkspaceTestResult(
            Optional<ResolveResult> resolveResult,
            List<WorkspaceBuildResult.MemberBuildResult> builtMembers,
            List<MemberTestRunResult> members,
            int totalMemberCount,
            Optional<Path> profileDirectory,
            WorkspaceTestToolchainMetrics toolchainMetrics) {
        this(
                resolveResult,
                builtMembers,
                members,
                totalMemberCount,
                profileDirectory,
                toolchainMetrics,
                WorkspaceTestPoolMetrics.empty());
    }

    public WorkspaceTestResult(
            Optional<ResolveResult> resolveResult,
            List<WorkspaceBuildResult.MemberBuildResult> builtMembers,
            List<MemberTestRunResult> members,
            int totalMemberCount,
            Optional<Path> profileDirectory) {
        this(
                resolveResult,
                builtMembers,
                members,
                totalMemberCount,
                profileDirectory,
                WorkspaceTestToolchainMetrics.empty());
    }

    public WorkspaceTestResult(
            Optional<ResolveResult> resolveResult,
            List<WorkspaceBuildResult.MemberBuildResult> builtMembers,
            List<MemberTestRunResult> members,
            int totalMemberCount) {
        this(
                resolveResult,
                builtMembers,
                members,
                totalMemberCount,
                Optional.empty(),
                WorkspaceTestToolchainMetrics.empty());
    }

    public WorkspaceTestResult(
            Optional<ResolveResult> resolveResult,
            List<WorkspaceBuildResult.MemberBuildResult> builtMembers,
            List<MemberTestRunResult> members) {
        this(
                resolveResult,
                builtMembers,
                members,
                members.size(),
                Optional.empty(),
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
                .map(MemberTestRunResult::member)
                .toList());
        return (int) builtMembers.stream()
                .map(WorkspaceBuildResult.MemberBuildResult::member)
                .filter(member -> !selected.contains(member))
                .count();
    }

    public int testSourceCount() {
        return members.stream()
                .map(MemberTestRunResult::result)
                .map(TestRunResult::compileResult)
                .mapToInt(result -> result.sourceCount())
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
                .map(MemberTestRunResult::result)
                .map(TestRunResult::compileResult)
                .filter(result -> result.testCompilationSkipped())
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
                .map(MemberTestRunResult::result)
                .map(TestRunResult::compileResult)
                .mapToLong(result -> result.testFingerprintCheckNanos())
                .sum();
    }

    public long testFingerprintWriteNanos() {
        return members.stream()
                .map(MemberTestRunResult::result)
                .map(TestRunResult::compileResult)
                .mapToLong(result -> result.testFingerprintWriteNanos())
                .sum();
    }

    public long mainFingerprintCheckMillis() {
        return mainFingerprintCheckNanos() / 1_000_000L;
    }

    public long mainFingerprintWriteMillis() {
        return mainFingerprintWriteNanos() / 1_000_000L;
    }

    public long testFingerprintCheckMillis() {
        return testFingerprintCheckNanos() / 1_000_000L;
    }

    public long testFingerprintWriteMillis() {
        return testFingerprintWriteNanos() / 1_000_000L;
    }

    public int testRuntimeClasspathEntryCount() {
        return members.stream()
                .map(MemberTestRunResult::result)
                .mapToInt(TestRunResult::testRuntimeClasspathEntries)
                .sum();
    }

    public int testLauncherClasspathEntryCount() {
        return members.stream()
                .map(MemberTestRunResult::result)
                .mapToInt(TestRunResult::testLauncherClasspathEntries)
                .sum();
    }

    public int testDiscoveryScanRootCount() {
        return members.stream()
                .map(MemberTestRunResult::result)
                .mapToInt(TestRunResult::testDiscoveryScanRoots)
                .sum();
    }

    public int testWorkerStartCount() {
        return members.stream()
                .map(MemberTestRunResult::result)
                .mapToInt(TestRunResult::testWorkerStarts)
                .sum();
    }

    public int testWorkerRequestCount() {
        return members.stream()
                .map(MemberTestRunResult::result)
                .mapToInt(TestRunResult::testWorkerRequests)
                .sum();
    }


    public int testClassSelectorCount() {
        return members.stream()
                .map(MemberTestRunResult::result)
                .map(TestRunResult::testSelection)
                .mapToInt(selection -> selection.classSelectors().size())
                .sum();
    }

    public int testMethodSelectorCount() {
        return members.stream()
                .map(MemberTestRunResult::result)
                .map(TestRunResult::testSelection)
                .mapToInt(selection -> selection.methodSelectors().size())
                .sum();
    }

    public int testPatternCount() {
        return members.stream()
                .map(MemberTestRunResult::result)
                .map(TestRunResult::testSelection)
                .mapToInt(selection -> selection.classNamePatterns().size())
                .sum();
    }

    public int testIncludedTagCount() {
        return members.stream()
                .map(MemberTestRunResult::result)
                .map(TestRunResult::testSelection)
                .mapToInt(selection -> selection.includedTags().size())
                .sum();
    }

    public int testExcludedTagCount() {
        return members.stream()
                .map(MemberTestRunResult::result)
                .map(TestRunResult::testSelection)
                .mapToInt(selection -> selection.excludedTags().size())
                .sum();
    }

    public record MemberTestRunResult(
            String member,
            TestRunResult result) {
    }
}
