package sh.zolt.workspace.coverage;

import sh.zolt.build.testruntime.TestRunResult;
import sh.zolt.project.CoverageSettings;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceTestResult;
import sh.zolt.workspace.service.WorkspaceTestToolchainMetrics;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record WorkspaceCoverageResult(
        Optional<ResolveResult> resolveResult,
        List<WorkspaceBuildResult.MemberBuildResult> builtMembers,
        List<MemberCoverageRunResult> members,
        String reportOutput,
        Path execFile,
        Optional<Path> xmlReport,
        Optional<Path> htmlDirectory,
        int classfileRootCount,
        int sourceRootCount,
        int totalMemberCount,
        WorkspaceTestToolchainMetrics toolchainMetrics,
        CoverageSettings coverageSettings) {
    public WorkspaceCoverageResult {
        resolveResult = resolveResult == null ? Optional.empty() : resolveResult;
        builtMembers = List.copyOf(builtMembers);
        members = List.copyOf(members);
        xmlReport = xmlReport == null ? Optional.empty() : xmlReport;
        htmlDirectory = htmlDirectory == null ? Optional.empty() : htmlDirectory;
        totalMemberCount = Math.max(totalMemberCount, members.size());
        toolchainMetrics = toolchainMetrics == null
                ? WorkspaceTestToolchainMetrics.empty()
                : toolchainMetrics;
        coverageSettings = coverageSettings == null
                ? CoverageSettings.none()
                : coverageSettings;
    }

    public WorkspaceCoverageResult(
            Optional<ResolveResult> resolveResult,
            List<WorkspaceBuildResult.MemberBuildResult> builtMembers,
            List<MemberCoverageRunResult> members,
            String reportOutput,
            Path execFile,
            Optional<Path> xmlReport,
            Optional<Path> htmlDirectory,
            int classfileRootCount,
            int sourceRootCount) {
        this(
                resolveResult,
                builtMembers,
                members,
                reportOutput,
                execFile,
                xmlReport,
                htmlDirectory,
                classfileRootCount,
                sourceRootCount,
                members.size(),
                WorkspaceTestToolchainMetrics.empty(),
                CoverageSettings.none());
    }

    public boolean resolvedLockfile() {
        return resolveResult.isPresent();
    }

    public WorkspaceTestResult testResult() {
        return new WorkspaceTestResult(
                resolveResult,
                builtMembers,
                members.stream()
                        .map(member -> new WorkspaceTestResult.MemberTestRunResult(member.member(), member.result()))
                        .toList(),
                totalMemberCount,
                Optional.empty(),
                toolchainMetrics);
    }

    public record MemberCoverageRunResult(
            String member,
            TestRunResult result) {
    }
}
