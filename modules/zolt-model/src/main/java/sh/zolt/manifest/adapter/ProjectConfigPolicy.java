package sh.zolt.manifest.adapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.DependencyConflictPolicy;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyDenyEntry;
import sh.zolt.manifest.LicensePolicyTerm;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.SpdxLicenseTerm;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredDependencyPolicy;
import sh.zolt.manifest.authored.AuthoredLicenseException;
import sh.zolt.manifest.authored.AuthoredLicensePolicy;
import sh.zolt.manifest.effective.EffectiveValue;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyConstraintKind;
import sh.zolt.project.DependencyPolicyExclusion;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.project.LicensePolicyException;
import sh.zolt.project.LicensePolicySettings;
import sh.zolt.project.UnknownLicensePolicy;

/**
 * Projects the final {@code [dependencies.policy]}, {@code [dependencies.constraints]}, and
 * {@code [dependencies.license-exceptions.<coordinate>]} domains onto the legacy
 * {@link DependencyPolicySettings}.
 */
final class ProjectConfigPolicy {
    private ProjectConfigPolicy() {
    }

    static DependencyPolicySettings policy(
            Optional<AuthoredDependencyPolicy> policy,
            Optional<AuthoredDependencyConstraints> constraints,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions) {
        return new DependencyPolicySettings(
                exclusions(policy),
                constraints(constraints, versions),
                policy.flatMap(AuthoredDependencyPolicy::conflicts)
                        .map(conflicts -> conflicts == DependencyConflictPolicy.FAIL)
                        .orElse(false),
                licenses(policy));
    }

    private static List<DependencyPolicyExclusion> exclusions(
            Optional<AuthoredDependencyPolicy> policy) {
        List<DependencyPolicyExclusion> exclusions = new ArrayList<>();
        for (DependencyDenyEntry entry : policy.map(AuthoredDependencyPolicy::deny).orElse(List.of())) {
            exclusions.add(new DependencyPolicyExclusion(
                    entry.coordinate().group(),
                    entry.coordinate().artifact(),
                    entry.reason()));
        }
        return List.copyOf(exclusions);
    }

    private static Map<String, DependencyConstraint> constraints(
            Optional<AuthoredDependencyConstraints> constraints,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions) {
        Map<String, DependencyConstraint> adapted = new LinkedHashMap<>();
        constraints.map(AuthoredDependencyConstraints::entries)
                .orElse(Map.of())
                .forEach((coordinate, constraint) ->
                        adapted.put(coordinate.value(), constraint(coordinate, constraint, versions)));
        return Map.copyOf(adapted);
    }

    private static DependencyConstraint constraint(
            DependencyCoordinate coordinate,
            AuthoredDependencyConstraint constraint,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions) {
        String subject = "[dependencies.constraints] `" + coordinate + "`";
        return switch (constraint.selector()) {
            case DependencyConstraintSelector.FixedVersion fixed -> new DependencyConstraint(
                    coordinate.value(),
                    fixed.value(),
                    Optional.empty(),
                    DependencyConstraintKind.STRICT,
                    constraint.reason());
            case DependencyConstraintSelector.VersionReference reference -> new DependencyConstraint(
                    coordinate.value(),
                    alias(versions, reference.alias(), subject),
                    Optional.of(reference.alias().value()),
                    DependencyConstraintKind.STRICT,
                    constraint.reason());
        };
    }

    private static LicensePolicySettings licenses(Optional<AuthoredDependencyPolicy> policy) {
        Optional<AuthoredLicensePolicy> licenses = policy.flatMap(AuthoredDependencyPolicy::licenses);
        Map<DependencyCoordinate, AuthoredLicenseException> exceptions =
                policy.map(AuthoredDependencyPolicy::licenseExceptions).orElse(Map.of());
        if (licenses.isEmpty() && exceptions.isEmpty()) {
            return LicensePolicySettings.defaults();
        }
        AuthoredLicensePolicy authored = licenses.orElse(null);
        return new LicensePolicySettings(
                authored == null ? List.of() : terms(authored.allow()),
                authored == null ? List.of() : terms(authored.deny()),
                authored == null
                        ? UnknownLicensePolicy.WARN
                        : authored.unknown().orElse(UnknownLicensePolicy.WARN),
                exceptions(exceptions));
    }

    private static Map<String, LicensePolicyException> exceptions(
            Map<DependencyCoordinate, AuthoredLicenseException> exceptions) {
        Map<String, LicensePolicyException> adapted = new LinkedHashMap<>();
        exceptions.forEach((coordinate, exception) -> adapted.put(
                coordinate.value(),
                new LicensePolicyException(
                        coordinate.value(),
                        exception.allow().stream().map(SpdxLicenseTerm::value).toList(),
                        exception.version(),
                        exception.reason())));
        return Map.copyOf(adapted);
    }

    private static List<String> terms(List<LicensePolicyTerm> terms) {
        return terms.stream().map(LicensePolicyTerm::value).toList();
    }

    private static String alias(
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            LocalId alias,
            String subject) {
        EffectiveValue<VersionAliasValue> value = versions.get(alias);
        if (value == null) {
            throw new IllegalArgumentException(
                    subject + " references undefined version alias `" + alias + "`.");
        }
        return value.value().value();
    }
}
