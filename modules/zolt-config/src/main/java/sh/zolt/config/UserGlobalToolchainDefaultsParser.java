package sh.zolt.config;

import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaFeatureVersion;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

final class UserGlobalToolchainDefaultsParser {
    private static final Set<String> DEFAULTS_KEYS = Set.of("toolchain");
    private static final Set<String> DEFAULT_TOOLCHAIN_KEYS = Set.of("java");
    private static final Set<String> JAVA_TOOLCHAIN_KEYS = Set.of("version", "distribution", "features", "policy");

    private UserGlobalToolchainDefaultsParser() {
    }

    static UserGlobalToolchainDefaults parse(TomlTable table) {
        if (table == null) {
            return UserGlobalToolchainDefaults.none();
        }
        validateKeys("defaults", table, DEFAULTS_KEYS);
        TomlTable toolchain = nestedTable(table, "toolchain", "defaults.toolchain");
        if (toolchain == null) {
            return UserGlobalToolchainDefaults.none();
        }
        validateKeys("defaults.toolchain", toolchain, DEFAULT_TOOLCHAIN_KEYS);
        TomlTable java = nestedTable(toolchain, "java", "defaults.toolchain.java");
        if (java == null) {
            return UserGlobalToolchainDefaults.none();
        }
        validateKeys("defaults.toolchain.java", java, JAVA_TOOLCHAIN_KEYS);
        String version = requiredFeatureVersion(java);
        JavaDistribution distribution = requiredDistribution(java);
        Set<JavaFeature> features = features(java);
        ToolchainPolicy policy = optionalPolicy(java).orElse(ToolchainPolicy.PREFER_MANAGED);
        return new UserGlobalToolchainDefaults(Optional.of(new JavaToolchainRequest(
                version,
                distribution,
                features,
                policy)));
    }

    static String source(TomlTable defaultsTable) {
        if (defaultsTable == null) {
            return UserGlobalConfigSources.BUILT_IN_DEFAULT;
        }
        TomlTable toolchain = nestedTable(defaultsTable, "toolchain", "defaults.toolchain");
        if (toolchain == null) {
            return UserGlobalConfigSources.BUILT_IN_DEFAULT;
        }
        return nestedTable(toolchain, "java", "defaults.toolchain.java") == null
                ? UserGlobalConfigSources.BUILT_IN_DEFAULT
                : UserGlobalConfigSources.USER_GLOBAL_CONFIG;
    }

    private static String requiredString(TomlTable table, String section, String key) {
        Object raw = table.get(List.of(key));
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new UserGlobalConfigException(
                    "Missing required [" + section + "]." + key + " in user global config. Use a non-empty string.");
        }
        return value.trim();
    }

    private static String requiredFeatureVersion(TomlTable table) {
        String version = requiredString(table, "defaults.toolchain.java", "version");
        if (!JavaFeatureVersion.isConcrete(version)) {
            throw new UserGlobalConfigException(
                    "Invalid value for [defaults.toolchain.java].version in user global config: `"
                            + version
                            + "`. Use a Java feature release number such as `21` or `25`; aliases such as `latest` are not supported.");
        }
        return version;
    }

    private static Optional<String> optionalString(TomlTable table, String section, String key) {
        Object raw = table.get(List.of(key));
        if (raw == null) {
            return Optional.empty();
        }
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new UserGlobalConfigException(
                    "Invalid value for [" + section + "]." + key + " in user global config. Use a non-empty string.");
        }
        return Optional.of(value.trim());
    }

    private static JavaDistribution requiredDistribution(TomlTable table) {
        String raw = requiredString(table, "defaults.toolchain.java", "distribution");
        return JavaDistribution.fromId(raw).orElseThrow(() -> new UserGlobalConfigException(
                "Unsupported value for [defaults.toolchain.java].distribution in user global config: `"
                        + raw
                        + "`. Use one of "
                        + JavaDistribution.supportedIds()
                        + "."));
    }

    private static Optional<ToolchainPolicy> optionalPolicy(TomlTable table) {
        Optional<String> raw = optionalString(table, "defaults.toolchain.java", "policy");
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ToolchainPolicy.fromId(raw.orElseThrow()).orElseThrow(() -> new UserGlobalConfigException(
                "Unsupported value for [defaults.toolchain.java].policy in user global config: `"
                        + raw.orElseThrow()
                        + "`. Use one of "
                        + ToolchainPolicy.supportedIds()
                        + ".")));
    }

    private static Set<JavaFeature> features(TomlTable table) {
        Object raw = table.get(List.of("features"));
        if (raw == null) {
            return Set.of();
        }
        if (!(raw instanceof TomlArray array)) {
            throw new UserGlobalConfigException(
                    "Invalid value for [defaults.toolchain.java].features in user global config. Use an array of strings.");
        }
        LinkedHashSet<JavaFeature> features = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            Object element = array.get(index);
            if (!(element instanceof String value) || value.isBlank()) {
                throw new UserGlobalConfigException(
                        "Invalid value for [defaults.toolchain.java].features[" + index + "] in user global config. Use a non-empty string.");
            }
            String normalized = value.trim();
            features.add(JavaFeature.fromId(normalized).orElseThrow(() -> new UserGlobalConfigException(
                    "Unsupported value for [defaults.toolchain.java].features in user global config: `"
                            + normalized
                            + "`. Use one of "
                            + JavaFeature.supportedIds()
                            + ".")));
        }
        return Collections.unmodifiableSet(features);
    }

    private static TomlTable nestedTable(TomlTable table, String key, String section) {
        Object raw = table.get(List.of(key));
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof TomlTable nested)) {
            throw new UserGlobalConfigException(
                    "Invalid value for [" + section + "] in user global config. Use a table.");
        }
        return nested;
    }

    private static void validateKeys(String section, TomlTable table, Set<String> allowedKeys) {
        for (String key : table.keySet()) {
            if (!allowedKeys.contains(key)) {
                throw new UserGlobalConfigException(
                        "Unknown key `" + key + "` in [" + section + "] in user global config.");
            }
        }
    }
}
