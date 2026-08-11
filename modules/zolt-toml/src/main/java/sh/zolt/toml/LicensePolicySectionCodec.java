package sh.zolt.toml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.tomlj.TomlTable;
import sh.zolt.license.SpdxExpression;
import sh.zolt.license.SpdxExpressionParseException;
import sh.zolt.license.SpdxExpressionParser;
import sh.zolt.project.LicensePolicyException;
import sh.zolt.project.LicensePolicySettings;
import sh.zolt.project.UnknownLicensePolicy;
import sh.zolt.project.VersionPolicy;
import sh.zolt.toml.support.TomlScalars;
import sh.zolt.toml.support.TomlValidation;

final class LicensePolicySectionCodec {
    private static final String SECTION = "dependencyPolicy.licenses";
    private static final Set<String> KEYS = Set.of("allow", "deny", "unknown", "exceptions");
    private static final Set<String> EXCEPTION_KEYS = Set.of("allow", "version", "reason");
    private static final SpdxExpressionParser SPDX = new SpdxExpressionParser();

    private LicensePolicySectionCodec() {
    }

    static LicensePolicySettings parse(TomlTable policyTable) {
        if (policyTable == null) {
            return LicensePolicySettings.defaults();
        }
        TomlTable table = policyTable.getTable(List.of("licenses"));
        if (table == null) {
            return LicensePolicySettings.defaults();
        }
        TomlValidation.validateKeysWithVersionRefHint(SECTION, table, KEYS);
        List<String> allow = policyTerms(table, "allow");
        List<String> deny = policyTerms(table, "deny");
        UnknownLicensePolicy unknown = unknown(table);
        Map<String, LicensePolicyException> exceptions = exceptions(table, allow, deny);
        return new LicensePolicySettings(allow, deny, unknown, exceptions);
    }

    static void write(StringBuilder toml, LicensePolicySettings settings) {
        if (settings == null || settings.isDefault()) {
            return;
        }
        toml.append("[").append(SECTION).append("]\n");
        if (!settings.allow().isEmpty()) {
            toml.append("allow = ").append(stringArray(settings.allow())).append('\n');
        }
        if (!settings.deny().isEmpty()) {
            toml.append("deny = ").append(stringArray(settings.deny())).append('\n');
        }
        if (settings.unknown() != UnknownLicensePolicy.WARN) {
            toml.append("unknown = ").append(quote(settings.unknown().configValue())).append('\n');
        }
        toml.append('\n');
        for (LicensePolicyException exception : new TreeMap<>(settings.exceptions()).values()) {
            toml.append("[").append(SECTION).append(".exceptions.")
                    .append(quote(exception.dependency())).append("]\n");
            toml.append("allow = ").append(stringArray(exception.allow())).append('\n');
            exception.version().ifPresent(version -> toml.append("version = ").append(quote(version)).append('\n'));
            toml.append("reason = ").append(quote(exception.reason())).append("\n\n");
        }
    }

    private static List<String> policyTerms(TomlTable table, String key) {
        List<String> values = TomlScalars.stringListOrDefault(table, SECTION, key, List.of());
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            try {
                normalized.add(SPDX.parseTerm(value).canonical());
            } catch (SpdxExpressionParseException exception) {
                if (SPDX.isExpressionShaped(value)) {
                    throw new ZoltConfigException("Invalid SPDX license term `"
                            + value
                            + "` in ["
                            + SECTION
                            + "]."
                            + key
                            + ": "
                            + exception.getMessage());
                }
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private static UnknownLicensePolicy unknown(TomlTable table) {
        String value = TomlScalars.stringOrDefault(
                table, SECTION, "unknown", UnknownLicensePolicy.WARN.configValue());
        return UnknownLicensePolicy.fromConfigValue(value)
                .orElseThrow(() -> new ZoltConfigException(
                        "Unsupported [dependencyPolicy.licenses].unknown value `"
                                + value
                                + "` in zolt.toml. Supported values are: "
                                + UnknownLicensePolicy.supportedValues()
                                + "."));
    }

    private static Map<String, LicensePolicyException> exceptions(
            TomlTable table,
            List<String> globalAllow,
            List<String> globalDeny) {
        TomlTable exceptions = table.getTable(List.of("exceptions"));
        if (exceptions == null) {
            return Map.of();
        }
        if (globalAllow.isEmpty()) {
            throw new ZoltConfigException(
                    "[dependencyPolicy.licenses].allow must be non-empty when scoped license exceptions are configured.");
        }
        Map<String, LicensePolicyException> parsed = new LinkedHashMap<>();
        for (String dependency : exceptions.keySet()) {
            validateCoordinate(dependency);
            String exceptionSection = SECTION + ".exceptions.\"" + dependency + "\"";
            TomlTable exception = exceptions.getTable(List.of(dependency));
            if (exception == null) {
                throw new ZoltConfigException(
                        "Invalid value for [" + exceptionSection + "] in zolt.toml. Use a table.");
            }
            TomlValidation.validateKeysWithVersionRefHint(exceptionSection, exception, EXCEPTION_KEYS);
            List<String> allow = exceptionTerms(exception, exceptionSection, globalDeny);
            Optional<String> version = optionalVersion(exception, exceptionSection);
            version.ifPresent(value -> validateVersion(exceptionSection, value));
            String reason = TomlScalars.requiredString(exception, exceptionSection, "reason");
            parsed.put(dependency, new LicensePolicyException(dependency, allow, version, reason));
        }
        return parsed;
    }

    private static List<String> exceptionTerms(
            TomlTable table,
            String section,
            List<String> globalDeny) {
        List<String> values = TomlScalars.stringListOrDefault(table, section, "allow", List.of());
        if (values.isEmpty()) {
            throw new ZoltConfigException("[" + section + "].allow must contain at least one SPDX license term.");
        }
        List<String> terms = new ArrayList<>();
        for (String value : values) {
            SpdxExpression term;
            try {
                term = SPDX.parseTerm(value);
            } catch (SpdxExpressionParseException exception) {
                throw new ZoltConfigException(
                        "Invalid SPDX license term `" + value + "` in [" + section + "].allow: "
                                + exception.getMessage());
            }
            String canonical = term.canonical();
            if (!canonical.equals(value)) {
                throw new ZoltConfigException("Non-canonical SPDX license term `"
                        + value
                        + "` in ["
                        + section
                        + "].allow. Use `"
                        + canonical
                        + "`.");
            }
            if (denied(term, globalDeny)) {
                throw new ZoltConfigException("Scoped exception term `"
                        + canonical
                        + "` in ["
                        + section
                        + "].allow is denied globally and cannot be overridden.");
            }
            terms.add(canonical);
        }
        return terms.stream().distinct().sorted().toList();
    }

    private static boolean denied(SpdxExpression term, List<String> globalDeny) {
        if (globalDeny.contains(term.canonical())) {
            return true;
        }
        return term instanceof SpdxExpression.With with && globalDeny.contains(with.licenseId());
    }

    private static void validateCoordinate(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        if (parts.length != 2
                || parts[0].isBlank()
                || parts[1].isBlank()
                || !coordinate.equals(coordinate.trim())
                || coordinate.chars().anyMatch(Character::isWhitespace)
                || coordinate.indexOf('*') >= 0) {
            throw new ZoltConfigException("Invalid license exception coordinate `"
                    + coordinate
                    + "` in ["
                    + SECTION
                    + ".exceptions]. Use an exact `group:artifact` without whitespace or wildcards.");
        }
    }

    private static Optional<String> optionalVersion(TomlTable table, String section) {
        Object raw = table.get(List.of("version"));
        if (raw == null) {
            return Optional.empty();
        }
        if (!(raw instanceof String version) || version.isBlank()) {
            throw new ZoltConfigException(
                    "Invalid [" + section + "].version in zolt.toml. Use a non-empty exact version string.");
        }
        return Optional.of(version);
    }

    private static void validateVersion(String section, String version) {
        VersionPolicy.violation(VersionPolicy.Context.EXTERNAL_DEPENDENCY, version)
                .ifPresent(violation -> {
                    throw new ZoltConfigException("Invalid ["
                            + section
                            + "].version `"
                            + version
                            + "`: "
                            + violation.guidance());
                });
    }

    private static String stringArray(List<String> values) {
        return values.stream().map(LicensePolicySectionCodec::quote).collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private static String quote(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
