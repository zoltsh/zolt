package sh.zolt.workspace.publish;

import sh.zolt.project.PackageMode;
import sh.zolt.publish.ManifestPublishSettingsLoader;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceSelection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selects and dependency-orders the publishable members of a family, and checks version uniformity. */
final class WorkspacePublishSelection {
    private WorkspacePublishSelection() {
    }

    /**
     * The selected members that declare a {@code [publish]} config, dependency-ordered: providers
     * before consumers (build order), the BOM last so consumers can already resolve it.
     */
    static List<WorkspaceMember> publishable(
            Workspace workspace, WorkspaceSelection selection, ManifestPublishSettingsLoader publishSettingsLoader) {
        Map<String, WorkspaceMember> byPath = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            byPath.put(member.path(), member);
        }
        List<WorkspaceMember> jarMembers = new ArrayList<>();
        List<WorkspaceMember> bomMembers = new ArrayList<>();
        for (String memberPath : selection.includedMembers()) {
            WorkspaceMember member = byPath.get(memberPath);
            if (member == null || !hasPublishConfig(member, publishSettingsLoader)) {
                continue;
            }
            if (member.config().packageSettings().mode() == PackageMode.BOM) {
                bomMembers.add(member);
            } else {
                jarMembers.add(member);
            }
        }
        List<WorkspaceMember> ordered = new ArrayList<>(jarMembers);
        ordered.addAll(bomMembers);
        return ordered;
    }

    private static boolean hasPublishConfig(WorkspaceMember member, ManifestPublishSettingsLoader publishSettingsLoader) {
        return publishSettingsLoader
                .read(member.directory().resolve("zolt.toml"))
                .configured();
    }

    /** A blocker when family members carry divergent versions (unless {@code --allow-mixed-versions}). */
    static List<String> uniformVersionBlockers(List<WorkspaceMember> publishable) {
        Set<String> versions = new LinkedHashSet<>();
        for (WorkspaceMember member : publishable) {
            versions.add(member.config().project().version());
        }
        if (versions.size() <= 1) {
            return List.of();
        }
        List<String> offenders = new ArrayList<>();
        for (WorkspaceMember member : publishable) {
            offenders.add(member.config().project().name() + "=" + member.config().project().version());
        }
        return List.of("family versions diverge (" + String.join(", ", offenders)
                + "). Align them, or pass --allow-mixed-versions to pin each member at its own version.");
    }
}
