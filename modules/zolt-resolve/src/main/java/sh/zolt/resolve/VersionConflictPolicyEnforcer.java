package sh.zolt.resolve;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.resolve.lockfile.assembly.ExecToolResolution;
import sh.zolt.resolve.version.VersionConflict;
import sh.zolt.resolve.version.VersionSelectionResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Enforces {@code [dependencies.policy].conflicts} against a resolved selection. Each isolated
 * exec-tool closure (Hole 1) resolves in its own right, so a conflict inside a tool never mediates against
 * the main graph or another tool; it must therefore be enforced per tool closure too, or a conflict inside
 * a tool would silently evade the policy. The main graph is enforced first (its error and behaviour stay
 * exactly as before) and tools follow in sorted name order, so the tool named in a failure is deterministic.
 */
final class VersionConflictPolicyEnforcer {
    private VersionConflictPolicyEnforcer() {
    }

    static List<String> enforce(
            DependencyPolicySettings dependencyPolicy,
            VersionSelectionResult mainSelection,
            List<ExecToolResolution> execResolutions,
            String retryCommand) {
        List<String> warnings = new ArrayList<>(enforce(dependencyPolicy, mainSelection, retryCommand, null));
        execResolutions.stream()
                .sorted(Comparator.comparing(ExecToolResolution::toolName))
                .forEach(tool -> warnings.addAll(
                        enforce(dependencyPolicy, tool.selection(), retryCommand, tool.toolName())));
        return List.copyOf(warnings);
    }

    private static List<String> enforce(
            DependencyPolicySettings dependencyPolicy,
            VersionSelectionResult selection,
            String retryCommand,
            String toolName) {
        if (dependencyPolicy == null
                || !(dependencyPolicy.failOnVersionConflict() || dependencyPolicy.warnOnVersionConflict())
                || selection.conflicts().isEmpty()) {
            return List.of();
        }
        List<String> conflicts = selection.conflicts().stream()
                .filter(VersionConflict::active)
                .sorted(Comparator
                        .comparing((VersionConflict conflict) -> conflict.packageId().toString())
                        .thenComparing(conflict -> conflict.variant().key()))
                .map(VersionConflictPolicyEnforcer::conflictDescription)
                .toList();
        if (conflicts.isEmpty()) {
            return List.of();
        }
        if (dependencyPolicy.warnOnVersionConflict()) {
            return List.of(warning(toolName, conflicts));
        }
        throw ResolveException.actionable(message(toolName), remediation(toolName, retryCommand, conflicts));
    }

    /**
     * Design §9.11: {@code warn} mediates and reports. The warning names exactly the conflicts the
     * {@code fail} remediation would have named, so the two policies differ only in whether the
     * mediated resolution stands.
     */
    private static String warning(String toolName, List<String> conflicts) {
        String where = toolName == null
                ? "Dependency version conflicts were mediated"
                : "Dependency version conflicts in the `" + toolName + "` exec-tool closure were mediated";
        return where
                + " and reported by [dependencies.policy].conflicts = \"warn\". Conflicts: "
                + String.join("; ", conflicts);
    }

    private static String message(String toolName) {
        if (toolName == null) {
            return "Dependency version conflicts are disallowed by [dependencies.policy].conflicts.";
        }
        return "Dependency version conflicts in the `"
                + toolName
                + "` exec-tool closure are disallowed by [dependencies.policy].conflicts.";
    }

    private static String remediation(String toolName, String retryCommand, List<String> conflicts) {
        String where = toolName == null
                ? "the conflicting versions"
                : "the conflicting versions in the `" + toolName + "` exec tool";
        return "Align "
                + where
                + " with a [platforms] BOM, a direct dependency, or a "
                + "[dependencies.constraints] strict constraint, then run `"
                + retryCommand
                + "` again. Conflicts: "
                + String.join("; ", conflicts);
    }

    private static String conflictDescription(VersionConflict conflict) {
        return conflict.packageId()
                + (conflict.variant().isDefault() ? "" : " variant " + conflict.variant().key())
                + " selected "
                + conflict.selectedVersion()
                + " ("
                + reason(conflict.selectionReason())
                + "), requested "
                + requestedVersions(conflict);
    }

    private static String requestedVersions(VersionConflict conflict) {
        return String.join(", ", conflict.requests().stream()
                .map(request -> request.requestedVersion()
                        + " ["
                        + request.origin().name().toLowerCase(Locale.ROOT)
                        + " "
                        + request.scope().lockfileName()
                        + "]")
                .distinct()
                .sorted()
                .toList());
    }

    private static String reason(ConflictSelectionReason reason) {
        return switch (reason) {
            case DIRECT_DEPENDENCY -> "direct dependency wins";
            case NEWEST_VERSION -> "newest version wins";
            case SELECTED_GRAPH -> "selected materialized graph wins";
        };
    }
}
