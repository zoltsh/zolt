package sh.zolt.workspace.clean;

import sh.zolt.build.CleanException;
import sh.zolt.build.clean.CleanResult;
import sh.zolt.build.clean.CleanService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceMemberSelector;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import sh.zolt.workspace.service.WorkspaceSelection;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkspaceCleanService {
    private final ManifestWorkspaceLoader workspaceLoader;
    private final WorkspaceMemberSelector memberSelector;
    private final CleanService cleanService;

    public WorkspaceCleanService() {
        this(new ManifestWorkspaceLoader(), new WorkspaceMemberSelector(), new CleanService());
    }

    WorkspaceCleanService(
            ManifestWorkspaceLoader workspaceLoader,
            WorkspaceMemberSelector memberSelector,
            CleanService cleanService) {
        this.workspaceLoader = workspaceLoader;
        this.memberSelector = memberSelector;
        this.cleanService = cleanService;
    }

    public WorkspaceCleanResult clean(Path startDirectory, WorkspaceSelectionRequest selectionRequest) {
        Path start = startDirectory.toAbsolutePath().normalize();
        Workspace workspace = workspaceLoader.discover(start).orElseThrow(() -> new WorkspaceConfigException(
                "Could not find workspace config. Run `zolt clean --workspace` from a workspace directory or add zolt.toml with [workspace]."));
        try (WorkspaceMutationLock ignored =
                WorkspaceMutationLock.acquire(workspace.root())) {
            return cleanLocked(workspace, selectionRequest);
        }
    }

    private WorkspaceCleanResult cleanLocked(
            Workspace workspace,
            WorkspaceSelectionRequest selectionRequest) {
        WorkspaceSelection selection = memberSelector.select(workspace, selectionRequest);
        Map<String, WorkspaceMember> membersByPath = membersByPath(workspace);
        List<WorkspaceCleanResult.MemberCleanResult> results = new ArrayList<>();
        for (String memberPath : selection.includedMembers()) {
            WorkspaceMember member = membersByPath.get(memberPath);
            try {
                CleanResult result = cleanService.clean(member.directory(), member.config());
                results.add(new WorkspaceCleanResult.MemberCleanResult(member.path(), result));
            } catch (CleanException exception) {
                throw new CleanException(
                        "Workspace member `"
                                + member.path()
                                + "` could not be cleaned. "
                                + exception.getMessage(),
                        exception);
            }
        }
        return new WorkspaceCleanResult(selection, results);
    }

    private static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }
}
