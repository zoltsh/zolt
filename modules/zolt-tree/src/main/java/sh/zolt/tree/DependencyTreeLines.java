package sh.zolt.tree;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The shared {@code +- } / {@code \- } renderer behind both the standalone and the workspace text
 * views. The two differ only in which edges and policies a package contributes — a standalone project
 * reads them straight off the lock entry, a workspace member reads its own member-qualified view — so
 * the drawing, cycle guard, conflict annotation, and child ordering live here once.
 */
final class DependencyTreeLines {
    /** Supplies the edges and policies one package contributes in the view being rendered. */
    interface View {
        List<String> dependencies(LockPackage lockPackage);

        List<String> policies(LockPackage lockPackage);
    }

    private final LockDependencyIndex index;
    private final Map<String, LockConflict> conflicts;
    private final View view;
    private final String regenerateCommand;

    DependencyTreeLines(
            LockDependencyIndex index,
            Map<String, LockConflict> conflicts,
            View view,
            String regenerateCommand) {
        this.index = index;
        this.conflicts = conflicts;
        this.view = view;
        this.regenerateCommand = regenerateCommand;
    }

    /** The lock's own view: edges and policies exactly as recorded on each package entry. */
    static View lockView() {
        return new View() {
            @Override
            public List<String> dependencies(LockPackage lockPackage) {
                return lockPackage.dependencies();
            }

            @Override
            public List<String> policies(LockPackage lockPackage) {
                return lockPackage.policies();
            }
        };
    }

    void writeRoots(
            StringBuilder output,
            List<DependencyRootProjection.Root> roots) {
        for (int index = 0; index < roots.size(); index++) {
            DependencyRootProjection.Root root = roots.get(index);
            if (root.selected().isEmpty()) {
                output.append(index == roots.size() - 1 ? "\\- " : "+- ")
                        .append(root.coordinate())
                        .append(" (").append(root.annotation()).append(")\n");
            } else {
                writePackage(
                        output,
                        root.selected().orElseThrow(),
                        "",
                        index == roots.size() - 1,
                        List.of(),
                        root.annotation());
            }
        }
    }

    private void writePackage(
            StringBuilder output,
            LockPackage lockPackage,
            String prefix,
            boolean last,
            List<String> ancestors,
            String rootAnnotation) {
        String coordinate = coordinate(lockPackage);
        output.append(prefix).append(last ? "\\- " : "+- ").append(coordinate);
        if (!rootAnnotation.isEmpty()) {
            output.append(" (").append(rootAnnotation).append(')');
        }
        LockConflict conflict = conflicts.get(qualifiedKey(lockPackage));
        if (conflict != null) {
            output.append(" (conflict: selected ")
                    .append(conflict.selectedVersion())
                    .append("; requested ")
                    .append(String.join(", ", conflict.requestedVersions().stream().sorted().toList()))
                    .append("; ")
                    .append(reason(conflict.reason()))
                    .append(')');
        }
        appendPolicies(output, view.policies(lockPackage));
        if (ancestors.contains(coordinate)) {
            output.append(" (cycle)");
        }
        output.append('\n');

        if (ancestors.contains(coordinate)) {
            return;
        }

        List<LockPackage> dependencies = view.dependencies(lockPackage).stream()
                .sorted()
                .map(edge -> index.resolveGraphEdge(edge, regenerateCommand).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        String childPrefix = prefix + (last ? "   " : "|  ");
        List<String> nextAncestors = new ArrayList<>(ancestors);
        nextAncestors.add(coordinate);
        for (int index = 0; index < dependencies.size(); index++) {
            writePackage(
                    output,
                    dependencies.get(index),
                    childPrefix,
                    index == dependencies.size() - 1,
                    nextAncestors,
                    "");
        }
    }

    private static void appendPolicies(StringBuilder output, List<String> policies) {
        if (policies.isEmpty()) {
            return;
        }
        output.append(" (policy: ")
                .append(String.join("; ", policies.stream().sorted().toList()))
                .append(')');
    }

    static Map<String, LockConflict> conflictsByPackage(ZoltLockfile lockfile) {
        Map<String, LockConflict> conflicts = new LinkedHashMap<>();
        lockfile.conflicts().stream()
                .sorted(Comparator.comparing(DependencyTreeLines::qualifiedKey))
                .forEach(conflict -> conflicts.put(qualifiedKey(conflict), conflict));
        return conflicts;
    }

    /** The {@code groupId:artifactId:version[:variant]} display coordinate of a lock entry. */
    static String coordinate(LockPackage lockPackage) {
        LockArtifactVariant variant = LockArtifactVariant.of(lockPackage);
        return lockPackage.packageId()
                + ":"
                + lockPackage.version()
                + (variant.isDefault() ? "" : ":" + variant.key());
    }

    private static String qualifiedKey(LockPackage lockPackage) {
        return lockPackage.packageId() + ":" + LockArtifactVariant.of(lockPackage).key();
    }

    private static String qualifiedKey(LockConflict conflict) {
        return conflict.packageId()
                + ":"
                + conflict.variant().map(LockArtifactVariant::key).orElse(LockArtifactVariant.defaultVariant().key());
    }

    private static String reason(ConflictSelectionReason reason) {
        return switch (reason) {
            case DIRECT_DEPENDENCY -> "direct dependency wins";
            case NEWEST_VERSION -> "newest version wins";
            case SELECTED_GRAPH -> "selected materialized graph wins";
        };
    }
}
