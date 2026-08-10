package sh.zolt.workspace.packaging;

import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceMember;
import java.util.LinkedHashMap;
import java.util.Map;

final class WorkspacePackagingIndex {
    private WorkspacePackagingIndex() {
    }

    static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }

    static Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath(WorkspaceBuildResult result) {
        Map<String, WorkspaceBuildResult.MemberBuildResult> builds = new LinkedHashMap<>();
        for (WorkspaceBuildResult.MemberBuildResult member : result.members()) {
            builds.put(member.member(), member);
        }
        return builds;
    }
}
