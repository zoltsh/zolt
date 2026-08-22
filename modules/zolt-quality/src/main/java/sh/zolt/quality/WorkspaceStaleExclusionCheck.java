package sh.zolt.quality;

import static sh.zolt.quality.QualityCheckCatalog.PROJECT_MODEL;

import sh.zolt.workspace.service.Workspace;
import java.util.List;
import java.util.Optional;

/**
 * Design §6.2: an authored {@code [workspace.members].exclude} entry that matched no expanded
 * candidate is allowed but reported by {@code zolt check --workspace} as stale configuration. It
 * belongs to the root manifest, so it is a member-independent warning that never fails the report.
 */
final class WorkspaceStaleExclusionCheck {
    private WorkspaceStaleExclusionCheck() {
    }

    static Optional<QualityCheckResult> check(Workspace workspace) {
        List<String> stale = workspace.staleExclusions();
        if (stale.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(QualityCheckResult.warning(
                PROJECT_MODEL,
                Optional.empty(),
                "[workspace.members].exclude",
                "Workspace "
                        + QualityCheckText.plural(stale.size(), "exclusion", "exclusions")
                        + " `"
                        + String.join("`, `", stale)
                        + "` matched no workspace member.",
                "Remove the stale "
                        + QualityCheckText.plural(stale.size(), "entry", "entries")
                        + " from [workspace.members].exclude, or correct the pattern to match the"
                        + " member it should remove."));
    }
}
