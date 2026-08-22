package sh.zolt.manifest.effective;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.WorkspaceMemberPath;

/** Deterministic graph facts derived from one complete effective workspace member set. */
public record EffectiveWorkspaceGraph(
        List<EffectiveWorkspaceDependencyEdge> workspaceDependencies,
        List<EffectiveManagedDependencyRequest> managedDependencies,
        Map<WorkspaceMemberPath, List<WorkspaceMemberPath>> resolvedBomMembers) {
    public EffectiveWorkspaceGraph {
        workspaceDependencies = sortedDistinct(
                workspaceDependencies, "Effective workspace dependencies");
        managedDependencies = sortedDistinct(
                managedDependencies, "Effective managed dependency requests");
        resolvedBomMembers = immutableBomMembers(resolvedBomMembers);
    }

    private static <T extends Comparable<? super T>> List<T> sortedDistinct(
            List<T> values,
            String label) {
        ArrayList<T> sorted = new ArrayList<>(
                ManifestModelValues.immutableList(values, label));
        sorted.sort(null);
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index - 1).compareTo(sorted.get(index)) == 0) {
                throw new IllegalArgumentException(
                        label + " must not contain duplicate `" + sorted.get(index) + "`.");
            }
        }
        return List.copyOf(sorted);
    }

    private static Map<WorkspaceMemberPath, List<WorkspaceMemberPath>> immutableBomMembers(
            Map<WorkspaceMemberPath, List<WorkspaceMemberPath>> values) {
        Objects.requireNonNull(values, "Resolved BOM members must not be null.");
        TreeMap<WorkspaceMemberPath, List<WorkspaceMemberPath>> sorted = new TreeMap<>();
        values.forEach((owner, members) -> sorted.put(
                Objects.requireNonNull(owner, "Resolved BOM owner must not be null."),
                ManifestModelValues.sortedDistinctList(members, "Resolved BOM member paths")));
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
}
