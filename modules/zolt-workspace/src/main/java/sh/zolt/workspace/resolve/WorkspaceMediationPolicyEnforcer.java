package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolutionVariant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WorkspaceMediationPolicyEnforcer {
    private WorkspaceMediationPolicyEnforcer() {
    }

    static void enforce(
            List<LockPackage> candidates,
            List<LockConflict> conflicts,
            Map<ResolutionVariant, String> selectedVersions,
            Map<String, ProjectConfig> configs,
            String retryCommand) {
        enforceStrictConstraints(candidates, selectedVersions, configs, retryCommand);
        enforceConflictPolicies(candidates, conflicts, configs, retryCommand);
    }

    private static void enforceStrictConstraints(
            List<LockPackage> candidates,
            Map<ResolutionVariant, String> selectedVersions,
            Map<String, ProjectConfig> configs,
            String retryCommand) {
        for (LockPackage candidate : candidates) {
            String selected = selectedVersions.get(new ResolutionVariant(
                    candidate.packageId(), LockArtifactVariant.of(candidate)));
            // A direct dependency already wins over a strict constraint inside one member. Enforce here
            // only when cross-member mediation would change that member's locally resolved version.
            if (selected == null || selected.equals(candidate.version())) {
                continue;
            }
            for (String member : candidate.members()) {
                ProjectConfig config = configs.get(member);
                if (config == null) {
                    continue;
                }
                DependencyConstraint constraint = config.dependencyPolicy()
                        .constraints()
                        .get(candidate.packageId().toString());
                if (constraint != null && !constraint.version().equals(selected)) {
                    throw ResolveException.actionable(
                            "Workspace mediation cannot override member `"
                                    + member
                                    + "` strict constraint for `"
                                    + candidate.packageId()
                                    + "` from `"
                                    + constraint.version()
                                    + "` to `"
                                    + selected
                                    + "`.",
                            "Align the workspace dependency versions with the member's "
                                    + "[dependencyConstraints] strict constraint, then run `"
                                    + retryCommand
                                    + "` again.");
                }
            }
        }
    }

    private static void enforceConflictPolicies(
            List<LockPackage> candidates,
            List<LockConflict> conflicts,
            Map<String, ProjectConfig> configs,
            String retryCommand) {
        Map<String, List<LockConflict>> conflictsByMember = new LinkedHashMap<>();
        for (LockConflict conflict : conflicts) {
            LockArtifactVariant variant =
                    conflict.variant().orElseGet(LockArtifactVariant::defaultVariant);
            Set<String> affectedMembers = new LinkedHashSet<>();
            candidates.stream()
                    .filter(candidate -> candidate.packageId().equals(conflict.packageId()))
                    .filter(candidate -> LockArtifactVariant.of(candidate).equals(variant))
                    .forEach(candidate -> affectedMembers.addAll(candidate.members()));
            for (String member : affectedMembers) {
                ProjectConfig config = configs.get(member);
                if (config != null
                        && config.dependencyPolicy().failOnVersionConflict()) {
                    conflictsByMember
                            .computeIfAbsent(member, ignored -> new ArrayList<>())
                            .add(conflict);
                }
            }
        }
        if (conflictsByMember.isEmpty()) {
            return;
        }
        String member = conflictsByMember.keySet().stream().sorted().findFirst().orElseThrow();
        String descriptions = String.join("; ", conflictsByMember.get(member).stream()
                .sorted(Comparator.comparing(conflict -> conflict.packageId().toString()))
                .map(WorkspaceMediationPolicyEnforcer::description)
                .toList());
        throw ResolveException.actionable(
                "Workspace dependency version conflicts affecting member `"
                        + member
                        + "` are disallowed by [dependencyPolicy].failOnVersionConflict.",
                "Align the conflicting versions with a workspace-wide direct dependency, [platforms] "
                        + "BOM, or [dependencyConstraints] strict constraint, then run `"
                        + retryCommand
                        + "` again. Conflicts: "
                        + descriptions);
    }

    private static String description(LockConflict conflict) {
        LockArtifactVariant variant =
                conflict.variant().orElseGet(LockArtifactVariant::defaultVariant);
        return conflict.packageId()
                + (variant.isDefault() ? "" : " variant " + variant.key())
                + " selected "
                + conflict.selectedVersion()
                + ", requested "
                + String.join(", ", conflict.requestedVersions());
    }
}
