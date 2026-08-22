package sh.zolt.workspace.publish;

import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Answers whether a directory IS an exact declared workspace member — the question that decides
 * whether a workspace member route applies at all.
 *
 * <p><strong>Config-only by construction.</strong> It reads the workspace config and its members'
 * {@code zolt.toml} and never looks at a lockfile. That is what lets a caller settle membership BEFORE
 * applying any workspace-lock gate: those gates apply to every directory beneath a discoverable
 * workspace root, so asking them first would refuse a standalone project that merely sits inside a
 * workspace tree over a lock it does not use.
 *
 * <p>Being under the root is not membership. A nested project the workspace never declares is not a
 * member, and neither is the workspace root itself — its own directory is where {@code --workspace}
 * runs, not a member build.
 */
public final class WorkspaceMemberDirectory {
    private final ManifestWorkspaceLoader workspaceLoader;

    public WorkspaceMemberDirectory() {
        this(new ManifestWorkspaceLoader());
    }

    public WorkspaceMemberDirectory(ManifestWorkspaceLoader workspaceLoader) {
        this.workspaceLoader = workspaceLoader;
    }

    /** The declared member whose directory is exactly {@code startDirectory}, if there is one. */
    public Optional<WorkspaceMember> at(Path startDirectory) {
        Path directory = startDirectory.toAbsolutePath().normalize();
        return workspaceLoader.discover(directory)
                .filter(candidate -> !candidate.root().toAbsolutePath().normalize().equals(directory))
                .flatMap(candidate -> candidate.members().stream()
                        .filter(member -> member.directory().toAbsolutePath().normalize().equals(directory))
                        .findFirst());
    }
}
