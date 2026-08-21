package sh.zolt.manifest.effective;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredWorkspace;

/** One workspace root and its complete, deterministically ordered effective member set. */
public record EffectiveWorkspace(
        AuthoredManifest root,
        Map<WorkspaceMemberPath, EffectiveManifest> members) {
    private static final WorkspaceMemberPath ROOT_MEMBER = new WorkspaceMemberPath(".");

    public EffectiveWorkspace {
        root = Objects.requireNonNull(root, "Authored workspace root must not be null.");
        AuthoredWorkspace workspace = root.workspace().orElseThrow(() ->
                new IllegalArgumentException("An effective workspace requires a [workspace] root domain."));
        members = ManifestModelValues.immutableSortedMap(
                members,
                WorkspaceMemberPath::compareTo,
                "Effective workspace member path",
                "Effective workspace member");
        if (members.isEmpty()) {
            throw new IllegalArgumentException("An effective workspace must contain at least one member.");
        }
        if (workspace.members().defaultMembers().isPresent()) {
            for (WorkspaceMemberPath path
                    : workspace.members().defaultMembers().orElseThrow()) {
                if (members.containsKey(path)) {
                    continue;
                }
                throw new IllegalArgumentException(
                        "Workspace default member `" + path
                                + "` is not in the effective member set.");
            }
        }
        validateMembers(root, workspace, members);
    }

    /** Authored workspace identity and membership declaration from {@link #root()}. */
    public AuthoredWorkspace workspace() {
        return root.workspace().orElseThrow();
    }

    private static void validateMembers(
            AuthoredManifest root,
            AuthoredWorkspace workspace,
            Map<WorkspaceMemberPath, EffectiveManifest> members) {
        Map<String, WorkspaceMemberPath> spellingByPortabilityKey = new HashMap<>();
        Map<ProjectIdentityKey, WorkspaceMemberPath> pathByIdentity = new HashMap<>();
        for (Map.Entry<WorkspaceMemberPath, EffectiveManifest> entry : members.entrySet()) {
            WorkspaceMemberPath path = entry.getKey();
            EffectiveManifest member = entry.getValue();
            WorkspaceMemberPath existing = spellingByPortabilityKey.putIfAbsent(
                    path.portabilityKey(), path);
            if (existing != null && !existing.equals(path)) {
                throw new IllegalArgumentException(
                        "Workspace member paths `" + existing + "` and `" + path
                                + "` collide under Unicode portability rules.");
            }
            WorkspaceContext context = member.workspace().orElseThrow(() ->
                    new IllegalArgumentException(
                            "Effective workspace member `" + path + "` lacks workspace context."));
            if (!context.memberPath().equals(path)) {
                throw new IllegalArgumentException(
                        "Effective workspace member key `" + path
                                + "` does not match its workspace context path `"
                                + context.memberPath() + "`.");
            }
            if (!context.name().value().equals(workspace.name())) {
                throw new IllegalArgumentException(
                        "Effective workspace member `" + path
                                + "` has a different workspace identity.");
            }
            if (path.equals(ROOT_MEMBER) && member.authored() != root) {
                throw new IllegalArgumentException(
                        "The `.` workspace member must reuse the authored workspace root.");
            }
            if (!path.equals(ROOT_MEMBER) && member.authored().workspace().isPresent()) {
                throw new IllegalArgumentException(
                        "Effective workspace member `" + path
                                + "` cannot contain a nested workspace domain.");
            }
            EffectiveProjectIdentity identity = member.project().identity();
            ProjectIdentityKey identityKey = new ProjectIdentityKey(
                    identity.group().value().value(), identity.name().value().value());
            WorkspaceMemberPath duplicate = pathByIdentity.putIfAbsent(identityKey, path);
            if (duplicate != null) {
                throw new IllegalArgumentException(
                        "Workspace members `" + duplicate + "` and `" + path
                                + "` have duplicate effective project identity `"
                                + identityKey.group() + ":" + identityKey.name() + "`.");
            }
        }
    }

    private record ProjectIdentityKey(String group, String name) {}
}
