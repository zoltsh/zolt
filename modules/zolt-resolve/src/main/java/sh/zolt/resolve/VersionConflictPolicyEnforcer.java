package sh.zolt.resolve;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.resolve.lockfile.assembly.CompilerToolResolution;
import sh.zolt.resolve.lockfile.assembly.ExecToolResolution;
import sh.zolt.resolve.version.VersionConflict;
import sh.zolt.resolve.version.VersionSelectionResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Enforces {@code [dependencies.policy].conflicts} against every independently resolved selection. The
 * main graph is enforced first (its error and behaviour stay exactly as before), then compiler closures
 * in stable scope order, then exec tools in sorted name order.
 */
final class VersionConflictPolicyEnforcer {
    private VersionConflictPolicyEnforcer() {
    }

    static List<String> enforce(
            DependencyPolicySettings dependencyPolicy,
            VersionSelectionResult mainSelection,
            List<ExecToolResolution> execResolutions,
            String retryCommand) {
        return enforce(
                dependencyPolicy,
                mainSelection,
                List.of(),
                execResolutions,
                retryCommand);
    }

    static List<String> enforce(
            DependencyPolicySettings dependencyPolicy,
            VersionSelectionResult mainSelection,
            List<CompilerToolResolution> compilerToolResolutions,
            List<ExecToolResolution> execResolutions,
            String retryCommand) {
        List<String> warnings = new ArrayList<>(enforce(
                dependencyPolicy,
                mainSelection,
                retryCommand,
                ConflictLocation.main()));
        CompilerToolResolution.ordered(compilerToolResolutions).forEach(resolution -> warnings.addAll(enforce(
                dependencyPolicy,
                resolution.selection(),
                retryCommand,
                ConflictLocation.compiler(resolution.compilerName()))));
        execResolutions.stream()
                .sorted(Comparator.comparing(ExecToolResolution::toolName))
                .forEach(tool -> warnings.addAll(
                        enforce(
                                dependencyPolicy,
                                tool.selection(),
                                retryCommand,
                                ConflictLocation.exec(tool.toolName()))));
        return List.copyOf(warnings);
    }

    private static List<String> enforce(
            DependencyPolicySettings dependencyPolicy,
            VersionSelectionResult selection,
            String retryCommand,
            ConflictLocation location) {
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
            return List.of(warning(location, conflicts));
        }
        throw ResolveException.actionable(message(location), remediation(location, retryCommand, conflicts));
    }

    /**
     * Design §9.11: {@code warn} mediates and reports. The warning names exactly the conflicts the
     * {@code fail} remediation would have named, so the two policies differ only in whether the
     * mediated resolution stands.
     */
    private static String warning(ConflictLocation location, List<String> conflicts) {
        return location.mediatedSubject()
                + " and reported by [dependencies.policy].conflicts = \"warn\". Conflicts: "
                + String.join("; ", conflicts);
    }

    private static String message(ConflictLocation location) {
        return location.disallowedSubject()
                + " are disallowed by [dependencies.policy].conflicts.";
    }

    private static String remediation(
            ConflictLocation location,
            String retryCommand,
            List<String> conflicts) {
        return "Align "
                + location.alignmentSubject()
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

    private record ConflictLocation(
            String mediatedSubject,
            String disallowedSubject,
            String alignmentSubject) {
        private static ConflictLocation main() {
            return new ConflictLocation(
                    "Dependency version conflicts were mediated",
                    "Dependency version conflicts",
                    "the conflicting versions");
        }

        private static ConflictLocation compiler(String compilerName) {
            return new ConflictLocation(
                    "Dependency version conflicts in the " + compilerName
                            + " compiler toolchain closure were mediated",
                    "Dependency version conflicts in the " + compilerName
                            + " compiler toolchain closure",
                    "the conflicting versions in the " + compilerName + " compiler toolchain");
        }

        private static ConflictLocation exec(String toolName) {
            return new ConflictLocation(
                    "Dependency version conflicts in the `" + toolName + "` exec-tool closure were mediated",
                    "Dependency version conflicts in the `" + toolName + "` exec-tool closure",
                    "the conflicting versions in the `" + toolName + "` exec tool");
        }
    }
}
