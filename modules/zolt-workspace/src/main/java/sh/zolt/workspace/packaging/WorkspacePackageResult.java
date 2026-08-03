package sh.zolt.workspace.packaging;

import sh.zolt.build.packaging.PackageResult;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import java.util.List;
import java.util.Optional;

public record WorkspacePackageResult(
        Optional<ResolveResult> resolveResult,
        List<WorkspaceBuildResult.MemberBuildResult> builtMembers,
        List<MemberPackageResult> members,
        int maxWorkers) {
    public WorkspacePackageResult {
        resolveResult = resolveResult == null ? Optional.empty() : resolveResult;
        builtMembers = List.copyOf(builtMembers);
        members = List.copyOf(members);
        maxWorkers = Math.max(0, maxWorkers);
    }

    public WorkspacePackageResult(
            Optional<ResolveResult> resolveResult,
            List<WorkspaceBuildResult.MemberBuildResult> builtMembers,
            List<MemberPackageResult> members) {
        this(resolveResult, builtMembers, members, members.isEmpty() ? 0 : 1);
    }

    public boolean resolvedLockfile() {
        return resolveResult.isPresent();
    }

    public int entryCount() {
        return members.stream()
                .map(MemberPackageResult::result)
                .mapToInt(PackageResult::entryCount)
                .sum();
    }

    public int packagedCount() {
        return (int) members.stream()
                .map(MemberPackageResult::result)
                .filter(result -> !result.packagingReused())
                .count();
    }

    public int reusedCount() {
        return members.size() - packagedCount();
    }

    public record MemberPackageResult(
            String member,
            PackageResult result) {
    }
}
