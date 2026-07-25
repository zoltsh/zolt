package sh.zolt.conflict;

import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.VersionComparator;
import java.util.Comparator;
import java.util.List;

public final class DependencyConflictFormatter {
    private final VersionComparator versionComparator = new VersionComparator();

    public String format(ZoltLockfile lockfile) {
        if (lockfile.conflicts().isEmpty()) {
            return "No dependency conflicts found.\n";
        }

        StringBuilder output = new StringBuilder("Dependency conflicts:\n");
        for (LockConflict conflict : sortedConflicts(lockfile.conflicts())) {
            output.append("- ").append(conflict.packageId()).append('\n');
            conflict.variant().ifPresent(variant ->
                    output.append("  variant: ").append(variant.key()).append('\n'));
            // Names the isolated exec-tool closure that mediated this conflict; absent for the main graph.
            conflict.toolGroup().ifPresent(tool -> output.append("  tool: ").append(tool).append('\n'));
            if (!conflict.members().isEmpty()) {
                output.append("  members: ")
                        .append(String.join(", ", conflict.members().stream().sorted().toList()))
                        .append('\n');
            }
            output.append("  selected: ")
                    .append(conflict.selectedVersion())
                    .append('\n')
                    .append("  requested: ")
                    .append(String.join(", ", sortedVersions(conflict.requestedVersions())))
                    .append('\n')
                    .append("  reason: ")
                    .append(reason(conflict.reason()))
                    .append('\n');
        }
        return output.toString();
    }

    private static List<LockConflict> sortedConflicts(List<LockConflict> conflicts) {
        return conflicts.stream()
                .sorted(Comparator
                        .comparing((LockConflict conflict) -> conflict.packageId().toString())
                        .thenComparing(conflict -> conflict.variant().map(LockArtifactVariant::key).orElse(""))
                        .thenComparing(conflict -> conflict.toolGroup().orElse("")))
                .toList();
    }

    private List<String> sortedVersions(List<String> versions) {
        return versions.stream()
                .sorted(versionComparator.thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private static String reason(ConflictSelectionReason reason) {
        return switch (reason) {
            case DIRECT_DEPENDENCY -> "direct dependency wins";
            case NEWEST_VERSION -> "newest version wins";
            case SELECTED_GRAPH -> "selected materialized graph wins";
        };
    }
}
