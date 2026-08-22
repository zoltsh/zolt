package sh.zolt.manifest.effective;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredWorkspace;

/** One workspace root and its complete, deterministically ordered effective member graph. */
public final class EffectiveWorkspace {
    private static final WorkspaceMemberPath ROOT_MEMBER = new WorkspaceMemberPath(".");

    private final AuthoredManifest root;
    private final Map<WorkspaceMemberPath, EffectiveManifest> members;
    private final EffectiveWorkspaceGraph graph;

    public EffectiveWorkspace(
            AuthoredManifest root,
            Map<WorkspaceMemberPath, EffectiveManifest> members) {
        this.root = Objects.requireNonNull(root, "Authored workspace root must not be null.");
        AuthoredWorkspace workspace = root.workspace().orElseThrow(() ->
                new IllegalArgumentException("An effective workspace requires a [workspace] root domain."));
        this.members = ManifestModelValues.immutableSortedMap(
                members,
                WorkspaceMemberPath::compareTo,
                "Effective workspace member path",
                "Effective workspace member");
        if (this.members.isEmpty()) {
            throw new IllegalArgumentException("An effective workspace must contain at least one member.");
        }
        validateDefaults(workspace, this.members);
        validateMembers(root, workspace, this.members);
        graph = EffectiveWorkspaceGraphComposer.compose(this.members);
    }

    public AuthoredManifest root() {
        return root;
    }

    public Map<WorkspaceMemberPath, EffectiveManifest> members() {
        return members;
    }

    /** Authored workspace identity and membership declaration from {@link #root()}. */
    public AuthoredWorkspace workspace() {
        return root.workspace().orElseThrow();
    }

    /** Resolved workspace-selector edges, managed requests, and BOM member selections. */
    public EffectiveWorkspaceGraph graph() {
        return graph;
    }

    private static void validateDefaults(
            AuthoredWorkspace workspace,
            Map<WorkspaceMemberPath, EffectiveManifest> members) {
        workspace.members().defaultMembers().ifPresent(defaults -> defaults.forEach(path -> {
            if (!members.containsKey(path)) {
                throw new IllegalArgumentException(
                        "Workspace default member `" + path
                                + "` is not in the effective member set.");
            }
        }));
    }

    private static void validateMembers(
            AuthoredManifest root,
            AuthoredWorkspace workspace,
            Map<WorkspaceMemberPath, EffectiveManifest> members) {
        Map<String, WorkspaceMemberPath> spellingByPortabilityKey = new HashMap<>();
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
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof EffectiveWorkspace workspace
                        && root.equals(workspace.root)
                        && members.equals(workspace.members);
    }

    @Override
    public int hashCode() {
        return Objects.hash(root, members);
    }

    @Override
    public String toString() {
        return "EffectiveWorkspace[root=" + root + ", members=" + members + "]";
    }
}
