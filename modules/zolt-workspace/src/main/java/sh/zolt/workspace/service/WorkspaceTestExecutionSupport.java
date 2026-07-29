package sh.zolt.workspace.service;

import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.build.profile.TestProfileMerger;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class WorkspaceTestExecutionSupport {
    private WorkspaceTestExecutionSupport() {
    }

    static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }

    static Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath(
            WorkspaceBuildResult result) {
        Map<String, WorkspaceBuildResult.MemberBuildResult> builds = new LinkedHashMap<>();
        for (WorkspaceBuildResult.MemberBuildResult member : result.members()) {
            builds.put(member.member(), member);
        }
        return builds;
    }

    static void closeTestWorkers(List<TestRunService> services) {
        RuntimeException firstFailure = null;
        for (TestRunService service : services.stream().distinct().toList()) {
            try {
                service.closeTestWorkers();
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    static void mergeProfiles(
            Optional<Path> workspaceProfileDirectory,
            List<WorkspaceTestResult.MemberTestRunResult> results) {
        workspaceProfileDirectory.ifPresent(directory -> TestProfileMerger.mergeProfiles(
                directory,
                results.stream()
                        .map(WorkspaceTestResult.MemberTestRunResult::result)
                        .map(result -> result.profileDirectory().map(path -> path.resolve("profile.json")))
                        .flatMap(Optional::stream)
                        .toList()));
    }
}
