package sh.zolt.workspace.discovery;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.manifest.effective.EffectiveWorkspace;
import sh.zolt.toml.manifest.ZoltManifestDocument;
import sh.zolt.workspace.service.WorkspaceInputs;

/** Complete final-language workspace, retained source documents, and discovery evidence. */
public record DiscoveredWorkspace(
        Path root,
        ZoltManifestDocument rootDocument,
        EffectiveWorkspace effective,
        Map<WorkspaceMemberPath, DiscoveredWorkspaceMember> members,
        WorkspaceMemberSelection selection,
        List<WorkspaceMemberPattern> staleExclusions,
        WorkspaceInputs inputs) {
    public DiscoveredWorkspace {
        root = Objects.requireNonNull(root, "Workspace root must not be null.")
                .toAbsolutePath()
                .normalize();
        Path normalizedRoot = root;
        Objects.requireNonNull(rootDocument, "Workspace root document must not be null.");
        Objects.requireNonNull(effective, "Effective workspace must not be null.");
        if (rootDocument.authored() != effective.root()) {
            throw new IllegalArgumentException(
                    "Discovered workspace root document must supply the effective workspace root.");
        }
        AuthoredWorkspaceMembers authoredMembership = rootDocument.authored()
                .workspace()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Discovered workspace root must declare a [workspace] domain."))
                .members();
        TreeMap<WorkspaceMemberPath, DiscoveredWorkspaceMember> sorted = new TreeMap<>();
        Objects.requireNonNull(members, "Discovered workspace members must not be null.")
                .forEach((path, member) -> {
                    Objects.requireNonNull(path, "Discovered workspace member path must not be null.");
                    Objects.requireNonNull(member, "Discovered workspace member must not be null.");
                    if (!path.equals(member.path())) {
                        throw new IllegalArgumentException(
                                "Discovered workspace member key `" + path
                                        + "` does not match evidence path `" + member.path() + "`.");
                    }
                    sorted.put(path, member);
                });
        if (!sorted.keySet().equals(effective.members().keySet())) {
            throw new IllegalArgumentException(
                    "Discovered and effective workspace member sets must match.");
        }
        for (Map.Entry<WorkspaceMemberPath, DiscoveredWorkspaceMember> entry : sorted.entrySet()) {
            WorkspaceMemberPath path = entry.getKey();
            DiscoveredWorkspaceMember member = entry.getValue();
            if (!member.directory().startsWith(normalizedRoot)) {
                throw new IllegalArgumentException(
                        "Discovered workspace member `" + path
                                + "` directory must remain beneath " + normalizedRoot + ".");
            }
            String relative = normalizedRoot.relativize(member.directory())
                    .toString()
                    .replace('\\', '/');
            WorkspaceMemberPath directoryPath = new WorkspaceMemberPath(
                    relative.isEmpty() ? "." : relative);
            if (!path.equals(directoryPath)) {
                throw new IllegalArgumentException(
                        "Discovered workspace member `" + path
                                + "` directory has logical path `" + directoryPath + "`.");
            }
            if (path.value().equals(".") && member.document() != rootDocument) {
                throw new IllegalArgumentException(
                        "The `.` workspace member must reuse the root manifest document.");
            }
            if (member.document().authored() != effective.members().get(path).authored()) {
                throw new IllegalArgumentException(
                        "Discovered workspace member `" + path
                                + "` must supply its effective authored manifest.");
            }
            for (WorkspaceMemberPattern evidence : member.matchedBy()) {
                if (!authoredMembership.include().contains(evidence)
                        || !WorkspaceMemberExpander.matches(evidence, path)) {
                    throw new IllegalArgumentException(
                            "Workspace member `" + path + "` has invalid include evidence `"
                                    + evidence + "`.");
                }
            }
        }
        members = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
        Map<WorkspaceMemberPath, DiscoveredWorkspaceMember> finalMembers = members;
        Objects.requireNonNull(selection, "Workspace member selection must not be null.");
        WorkspaceMemberSelection expectedSelection = authoredMembership.defaultMembers()
                .map(defaults -> new WorkspaceMemberSelection(
                        WorkspaceMemberSelection.Source.EXPLICIT_DEFAULT, defaults))
                .orElseGet(() -> new WorkspaceMemberSelection(
                        WorkspaceMemberSelection.Source.IMPLICIT_ALL,
                        new ArrayList<>(finalMembers.keySet())));
        if (!selection.equals(expectedSelection)) {
            throw new IllegalArgumentException(
                    "Workspace selection must exactly match the authored default or implicit-all member set.");
        }
        ArrayList<WorkspaceMemberPattern> stale = new ArrayList<>(
                Objects.requireNonNull(staleExclusions, "Stale exclusions must not be null."));
        stale.sort(null);
        for (int index = 1; index < stale.size(); index++) {
            if (stale.get(index - 1).equals(stale.get(index))) {
                throw new IllegalArgumentException(
                        "Stale workspace exclusions must not contain duplicate `"
                                + stale.get(index) + "`.");
            }
        }
        if (!authoredMembership.exclude().containsAll(stale)) {
            throw new IllegalArgumentException(
                    "Stale workspace exclusions must be authored workspace excludes.");
        }
        staleExclusions = List.copyOf(stale);
        Objects.requireNonNull(inputs, "Workspace input snapshot must not be null.");
    }

    public Path manifestPath() {
        return root.resolve("zolt.toml");
    }
}
