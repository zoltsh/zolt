package sh.zolt.toml;

import sh.zolt.toml.support.TomlVersions;
import sh.zolt.toml.support.TomlValidation;
import sh.zolt.toml.support.TomlScalars;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyConstraintKind;
import sh.zolt.project.DependencyPolicyExclusion;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.project.LicensePolicySettings;
import sh.zolt.project.VersionPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

final class DependencyPolicySectionCodec {
    private static final Set<String> DEPENDENCY_POLICY_KEYS = Set.of("exclude", "failOnVersionConflict", "licenses");
    private static final Set<String> DEPENDENCY_POLICY_EXCLUSION_KEYS = Set.of("group", "artifact", "reason");
    private static final Set<String> DEPENDENCY_CONSTRAINT_KEYS = Set.of("version", "versionRef", "kind", "reason");

    private DependencyPolicySectionCodec() {
    }

    static DependencyPolicySettings parse(
            TomlTable policyTable,
            TomlTable constraintsTable,
            Map<String, String> versionAliases) {
        List<DependencyPolicyExclusion> exclusions = dependencyPolicyExclusions(policyTable);
        boolean failOnVersionConflict = failOnVersionConflict(policyTable);
        Map<String, DependencyConstraint> constraints = dependencyConstraints(constraintsTable, versionAliases);
        LicensePolicySettings licenses = LicensePolicySectionCodec.parse(policyTable);
        if (exclusions.isEmpty() && constraints.isEmpty() && !failOnVersionConflict && licenses.isDefault()) {
            return DependencyPolicySettings.defaults();
        }
        return new DependencyPolicySettings(exclusions, constraints, failOnVersionConflict, licenses);
    }

    static void write(StringBuilder toml, DependencyPolicySettings policy) {
        if (policy == null || policy.equals(DependencyPolicySettings.defaults())) {
            return;
        }
        if (policy.failOnVersionConflict() || !policy.exclusions().isEmpty()) {
            toml.append("[dependencyPolicy]\n");
            if (policy.failOnVersionConflict()) {
                toml.append("failOnVersionConflict = true\n");
            }
            if (!policy.exclusions().isEmpty()) {
                toml.append("exclude = [");
                for (int index = 0; index < policy.exclusions().size(); index++) {
                    if (index > 0) {
                        toml.append(", ");
                    }
                    toml.append(policyExclusion(policy.exclusions().get(index)));
                }
                toml.append("]\n");
            }
            toml.append('\n');
        }
        LicensePolicySectionCodec.write(toml, policy.licenses());
        if (!policy.constraints().isEmpty()) {
            toml.append("[dependencyConstraints]\n");
            for (DependencyConstraint constraint : new TreeMap<>(policy.constraints()).values()) {
                toml.append(quote(constraint.coordinate())).append(" = { ");
                constraint.versionRef()
                        .ifPresentOrElse(
                                versionRef -> toml.append("versionRef = ").append(quote(versionRef)),
                                () -> toml.append("version = ").append(quote(constraint.version())));
                toml.append(", kind = ").append(quote(constraint.kind().configValue()));
                constraint.reason().ifPresent(reason -> toml.append(", reason = ").append(quote(reason)));
                toml.append(" }\n");
            }
            toml.append('\n');
        }
    }

    private static List<DependencyPolicyExclusion> dependencyPolicyExclusions(TomlTable table) {
        if (table == null) {
            return List.of();
        }
        TomlValidation.validateKeysWithVersionRefHint("dependencyPolicy", table, DEPENDENCY_POLICY_KEYS);
        Object rawExclusions = table.get(List.of("exclude"));
        if (rawExclusions == null) {
            return List.of();
        }
        if (!(rawExclusions instanceof TomlArray array)) {
            throw new ZoltConfigException(
                    "Invalid value for [dependencyPolicy].exclude in zolt.toml. Use an array of { group, artifact, reason } tables.");
        }
        List<DependencyPolicyExclusion> exclusions = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            if (!(array.get(index) instanceof TomlTable exclusion)) {
                throw new ZoltConfigException(
                        "Invalid value for [dependencyPolicy].exclude[" + index + "] in zolt.toml. Use { group = \"...\", artifact = \"...\" }.");
            }
            String section = "dependencyPolicy.exclude[" + index + "]";
            TomlValidation.validateKeysWithVersionRefHint(section, exclusion, DEPENDENCY_POLICY_EXCLUSION_KEYS);
            exclusions.add(new DependencyPolicyExclusion(
                    TomlScalars.requiredString(exclusion, section, "group"),
                    TomlScalars.requiredString(exclusion, section, "artifact"),
                    TomlScalars.optionalString(exclusion, section, "reason")));
        }
        return List.copyOf(exclusions);
    }

    private static boolean failOnVersionConflict(TomlTable table) {
        if (table == null) {
            return false;
        }
        return TomlScalars.booleanOrDefault(
                table,
                "dependencyPolicy",
                "failOnVersionConflict",
                false);
    }

    private static Map<String, DependencyConstraint> dependencyConstraints(
            TomlTable table,
            Map<String, String> versionAliases) {
        if (table == null) {
            return Map.of();
        }
        Map<String, DependencyConstraint> constraints = new LinkedHashMap<>();
        for (String coordinate : table.keySet()) {
            validatePackageCoordinate("dependencyConstraints", coordinate);
            TomlTable constraintTable = table.getTable(List.of(coordinate));
            if (constraintTable == null) {
                throw new ZoltConfigException(
                        "Invalid value for [dependencyConstraints]."
                                + coordinate
                                + " in zolt.toml. Use { version = \"...\", kind = \"strict\" }.");
            }
            TomlValidation.validateKeysWithVersionRefHint(
                    "dependencyConstraints." + coordinate,
                    constraintTable,
                    DEPENDENCY_CONSTRAINT_KEYS);
            Optional<String> version = TomlVersions.optionalVersionOrRef(
                    constraintTable,
                    "dependencyConstraints." + coordinate,
                    VersionPolicy.Context.CONSTRAINT,
                    versionAliases);
            Optional<String> versionRef = TomlVersions.optionalVersionRef(
                    constraintTable,
                    "dependencyConstraints." + coordinate,
                    versionAliases);
            String kindValue = TomlScalars.stringOrDefault(
                    constraintTable,
                    "dependencyConstraints." + coordinate,
                    "kind",
                    DependencyConstraintKind.STRICT.configValue());
            DependencyConstraintKind kind = DependencyConstraintKind.fromConfigValue(kindValue)
                    .orElseThrow(() -> new ZoltConfigException(
                            "Unsupported dependency constraint kind `"
                                    + kindValue
                                    + "` in zolt.toml. Supported dependency constraint kinds are: "
                                    + DependencyConstraintKind.supportedValues()
                                    + "."));
            constraints.put(coordinate, new DependencyConstraint(
                    coordinate,
                    version.orElseThrow(() -> new ZoltConfigException(
                            "Missing required field [dependencyConstraints."
                                    + coordinate
                                    + "].version in zolt.toml. Add version or versionRef.")),
                    versionRef,
                    kind,
                    TomlScalars.optionalString(constraintTable, "dependencyConstraints." + coordinate, "reason")));
        }
        return constraints;
    }

    private static String policyExclusion(DependencyPolicyExclusion exclusion) {
        List<String> parts = new ArrayList<>();
        parts.add("group = " + quote(exclusion.group()));
        parts.add("artifact = " + quote(exclusion.artifact()));
        exclusion.reason().ifPresent(reason -> parts.add("reason = " + quote(reason)));
        return "{ " + String.join(", ", parts) + " }";
    }

    private static void validatePackageCoordinate(String section, String coordinate) {
        if (coordinate == null || coordinate.isBlank() || !coordinate.equals(coordinate.trim())) {
            throw new ZoltConfigException(
                    "Invalid coordinate in ["
                            + section
                            + "]. Use `group:artifact` without leading or trailing whitespace.");
        }
        for (int index = 0; index < coordinate.length(); index++) {
            if (Character.isWhitespace(coordinate.charAt(index))) {
                throw new ZoltConfigException(
                        "Invalid coordinate `"
                                + coordinate
                                + "` in ["
                                + section
                                + "]. Use `group:artifact` without whitespace.");
            }
        }
        String[] parts = coordinate.split(":", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new ZoltConfigException(
                    "Invalid coordinate `"
                            + coordinate
                            + "` in ["
                            + section
                            + "]. Use `group:artifact`.");
        }
    }

    private static String quote(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
