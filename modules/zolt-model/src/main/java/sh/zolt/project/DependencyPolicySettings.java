package sh.zolt.project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Design §9.11: {@code conflicts} is a three-valued symbol. {@code resolve} mediates silently,
 * {@code warn} mediates and emits a structured warning, and {@code fail} rejects the resolution. The
 * policy is carried whole so {@code warn} cannot degrade into {@code resolve}.
 */
public record DependencyPolicySettings(
        List<DependencyPolicyExclusion> exclusions,
        Map<String, DependencyConstraint> constraints,
        VersionConflictPolicy conflicts,
        LicensePolicySettings licenses) {
    public DependencyPolicySettings(
            List<DependencyPolicyExclusion> exclusions,
            Map<String, DependencyConstraint> constraints) {
        this(exclusions, constraints, false);
    }

    public DependencyPolicySettings(
            List<DependencyPolicyExclusion> exclusions,
            Map<String, DependencyConstraint> constraints,
            boolean failOnVersionConflict) {
        this(exclusions, constraints, failOnVersionConflict, LicensePolicySettings.defaults());
    }

    public DependencyPolicySettings(
            List<DependencyPolicyExclusion> exclusions,
            Map<String, DependencyConstraint> constraints,
            boolean failOnVersionConflict,
            LicensePolicySettings licenses) {
        this(
                exclusions,
                constraints,
                failOnVersionConflict ? VersionConflictPolicy.FAIL : VersionConflictPolicy.RESOLVE,
                licenses);
    }

    public DependencyPolicySettings {
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        constraints = constraints == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(constraints));
        conflicts = conflicts == null ? VersionConflictPolicy.RESOLVE : conflicts;
        licenses = licenses == null ? LicensePolicySettings.defaults() : licenses;
    }

    public static DependencyPolicySettings defaults() {
        return new DependencyPolicySettings(
                List.of(), Map.of(), VersionConflictPolicy.RESOLVE, LicensePolicySettings.defaults());
    }

    public boolean failOnVersionConflict() {
        return conflicts == VersionConflictPolicy.FAIL;
    }

    public boolean warnOnVersionConflict() {
        return conflicts == VersionConflictPolicy.WARN;
    }

    public DependencyPolicySettings withConstraints(Map<String, DependencyConstraint> updatedConstraints) {
        return new DependencyPolicySettings(exclusions, updatedConstraints, conflicts, licenses);
    }
}
