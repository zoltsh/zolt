package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import sh.zolt.workspace.state.WorkspaceMemberState;
import sh.zolt.workspace.state.WorkspaceState;
import sh.zolt.workspace.state.WorkspaceStateStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Commits test-lane workspace state only after the selected members compiled successfully. */
public final class WorkspaceTestStateCommitter {
    private final WorkspaceStateStore stateStore = new WorkspaceStateStore();

    public void commitSelected(
            WorkspaceBuildPlan plan,
            Set<String> pendingTestCompiles) {
        if (pendingTestCompiles.isEmpty()) {
            return;
        }
        WorkspaceMutationLock.withLock(
                plan.workspace().root(),
                () -> {
                    commitLocked(plan.requireInputsCurrent(), pendingTestCompiles);
                    return null;
                });
    }

    private void commitLocked(
            WorkspaceBuildPlan plan,
            Set<String> pendingTestCompiles) {
        WorkspaceExecutionContext context = plan.executionContext();
        WorkspaceState recorded = stateStore.read(plan.workspace().root());
        Map<String, WorkspaceMemberState> current =
                new LinkedHashMap<>(recorded.members());
        Map<String, WorkspaceMember> membersByPath = membersByPath(plan.workspace());
        WorkspaceMemberStateObserver observer =
                new WorkspaceMemberStateObserver(context, membersByPath);
        for (String memberPath : plan.selection().selectedMembers()) {
            if (!pendingTestCompiles.contains(memberPath)) {
                continue;
            }
            WorkspaceMember member = requiredMember(membersByPath, memberPath);
            WorkspaceMemberState state = recorded.member(memberPath)
                    .orElseThrow(() -> new BuildException(
                            "Workspace state is missing member `" + memberPath
                                    + "` after its main build."));
            WorkspaceMemberStateObserver.TestCompilationState test =
                    observer.successfulTestCompilation(
                            member,
                            state.mainOutputManifestDigest());
            current.put(
                    memberPath,
                    state.withTestCompilation(
                            test.compileKey(),
                            test.resourceTreeDigest(),
                            test.outputManifestDigest()));
        }
        stateStore.write(
                plan.workspace().root(),
                new WorkspaceState(current, context.fileSnapshot().state()));
    }

    private static WorkspaceMember requiredMember(
            Map<String, WorkspaceMember> membersByPath,
            String memberPath) {
        WorkspaceMember member = membersByPath.get(memberPath);
        if (member == null) {
            throw new BuildException(
                    "Workspace selection names unknown member `" + memberPath + "`.");
        }
        return member;
    }

    private static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }
}
