package sh.zolt.project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DependencyPolicySettings(
        List<DependencyPolicyExclusion> exclusions,
        Map<String, DependencyConstraint> constraints,
        boolean failOnVersionConflict,
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

    public DependencyPolicySettings {
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        constraints = constraints == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(constraints));
        licenses = licenses == null ? LicensePolicySettings.defaults() : licenses;
    }

    public static DependencyPolicySettings defaults() {
        return new DependencyPolicySettings(List.of(), Map.of(), false, LicensePolicySettings.defaults());
    }

    public DependencyPolicySettings withConstraints(Map<String, DependencyConstraint> updatedConstraints) {
        return new DependencyPolicySettings(exclusions, updatedConstraints, failOnVersionConflict, licenses);
    }
}
