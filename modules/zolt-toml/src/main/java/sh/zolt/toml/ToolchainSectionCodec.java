package sh.zolt.toml;

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
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

public final class ToolchainSectionCodec {
    private static final Set<String> TOOLCHAIN_KEYS = Set.of("zolt", "java");
    private static final Set<String> ZOLT_KEYS = Set.of("version");
    private static final Set<String> JAVA_KEYS = Set.of("version", "distribution", "features", "policy", "test");
    private static final Set<String> JAVA_TEST_KEYS = Set.of("version", "distribution", "features");

    private ToolchainSectionCodec() {
    }

    public static Optional<String> parseZoltVersion(TomlParseResult result, String sourceName) {
        TomlTable toolchain = result.getTable("toolchain");
        if (toolchain == null) {
            return Optional.empty();
        }
        validateKeys("toolchain", toolchain, TOOLCHAIN_KEYS, sourceName);

        TomlTable zolt = nestedTable(toolchain, "zolt", "[toolchain].zolt", sourceName);
        if (zolt == null) {
            return Optional.empty();
        }
        validateKeys("toolchain.zolt", zolt, ZOLT_KEYS, sourceName);

        Object rawVersion = zolt.get(List.of("version"));
        if (rawVersion == null) {
            throw new ZoltConfigException(
                    "Missing required field [toolchain.zolt].version in " + sourceName + ".");
        }
        if (!(rawVersion instanceof String version) || version.isBlank()) {
            throw new ZoltConfigException(
                    "Invalid value for [toolchain.zolt].version in " + sourceName + ". Use a non-empty string.");
        }
        return Optional.of(safeVersion(version, sourceName));
    }

    public static Optional<JavaToolchainRequest> parseJavaToolchain(TomlParseResult result, String sourceName) {
        TomlTable toolchain = result.getTable("toolchain");
        if (toolchain == null) {
            return Optional.empty();
        }
        validateKeys("toolchain", toolchain, TOOLCHAIN_KEYS, sourceName);

        TomlTable java = nestedTable(toolchain, "java", "[toolchain].java", sourceName);
        if (java == null) {
            return Optional.empty();
        }
        validateKeys("toolchain.java", java, JAVA_KEYS, sourceName);

        String version = requiredFeatureVersion(java, "toolchain.java", sourceName);
        Optional<JavaDistribution> distribution = optionalDistribution(java, "toolchain.java", sourceName);
        Set<JavaFeature> features = features(java, "toolchain.java", sourceName);
        ToolchainPolicy policy = optionalPolicy(java, sourceName).orElse(ToolchainPolicy.PREFER_MANAGED);
        return Optional.of(new JavaToolchainRequest(version, distribution, features, policy));
    }

    /**
     * Parses the optional {@code [toolchain.java.test]} scoped entry that pins the JDK used to
     * <em>run</em> tests (compile stays on the main toolchain). It sets its own {@code version}
     * (and optional {@code distribution} and {@code features}), inheriting omitted values and
     * {@code policy} from the main {@code [toolchain.java]} entry. An equal-version entry with no
     * overrides is byte-identical to the main request and resolves to the same locked toolchain.
     */
    public static Optional<JavaToolchainRequest> parseJavaTestToolchain(
            TomlParseResult result, String sourceName, JavaToolchainRequest main) {
        TomlTable toolchain = result.getTable("toolchain");
        if (toolchain == null) {
            return Optional.empty();
        }
        TomlTable java = nestedTable(toolchain, "java", "[toolchain].java", sourceName);
        if (java == null) {
            return Optional.empty();
        }
        TomlTable test = nestedTable(java, "test", "[toolchain.java].test", sourceName);
        if (test == null) {
            return Optional.empty();
        }
        validateKeys("toolchain.java.test", test, JAVA_TEST_KEYS, sourceName);
        if (main == null) {
            throw new ZoltConfigException(
                    "[toolchain.java.test] needs a [toolchain.java] entry to inherit from in "
                            + sourceName
                            + ". Add [toolchain.java] with version and distribution.");
        }
        String version = requiredFeatureVersion(test, "toolchain.java.test", sourceName);
        Optional<JavaDistribution> distribution =
                optionalDistribution(test, "toolchain.java.test", sourceName).or(main::distribution);
        Set<JavaFeature> features = test.get(List.of("features")) == null
                ? main.features()
                : features(test, "toolchain.java.test", sourceName);
        return Optional.of(new JavaToolchainRequest(version, distribution, features, main.policy()));
    }

    private static void validateKeys(String section, TomlTable table, Set<String> allowedKeys, String sourceName) {
        for (String key : table.keySet()) {
            if (!allowedKeys.contains(key)) {
                throw new ZoltConfigException(
                        "Unknown field [" + section + "]." + key + " in " + sourceName + ". Remove it or check the spelling.");
            }
        }
    }

    private static TomlTable nestedTable(TomlTable table, String key, String subject, String sourceName) {
        Object raw = table.get(List.of(key));
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof TomlTable nested)) {
            throw new ZoltConfigException(
                    "Invalid value for " + subject + " in " + sourceName + ". Use a table.");
        }
        return nested;
    }

    private static String safeVersion(String version, String sourceName) {
        String normalized = version.strip();
        if (normalized.contains("/")
                || normalized.contains("\\")
                || normalized.contains("..")
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new ZoltConfigException(
                    "Invalid value for [toolchain.zolt].version in " + sourceName + ". Use one Zolt version string.");
        }
        return normalized;
    }

    private static String requiredString(TomlTable table, String section, String key, String sourceName) {
        Object rawValue = table.get(List.of(key));
        if (!(rawValue instanceof String value) || value.isBlank()) {
            throw new ZoltConfigException(
                    "Missing required field [" + section + "]." + key + " in " + sourceName + ". Add a non-empty string value.");
        }
        return value.strip();
    }

    private static String requiredFeatureVersion(TomlTable table, String section, String sourceName) {
        String version = requiredString(table, section, "version", sourceName);
        if (!JavaFeatureVersion.isConcrete(version)) {
            throw new ZoltConfigException(
                    "Invalid value for ["
                            + section
                            + "].version in "
                            + sourceName
                            + ": `"
                            + version
                            + "`. Use a Java feature release number such as `21` or `25`; aliases such as `latest` are not supported.");
        }
        return version;
    }

    private static Optional<String> optionalString(TomlTable table, String section, String key, String sourceName) {
        Object rawValue = table.get(List.of(key));
        if (rawValue == null) {
            return Optional.empty();
        }
        if (!(rawValue instanceof String value) || value.isBlank()) {
            throw new ZoltConfigException(
                    "Invalid value for [" + section + "]." + key + " in " + sourceName + ". Use a non-empty string value.");
        }
        return Optional.of(value.strip());
    }

    private static Optional<JavaDistribution> optionalDistribution(TomlTable table, String section, String sourceName) {
        Optional<String> raw = optionalString(table, section, "distribution", sourceName);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(JavaDistribution.fromId(raw.orElseThrow()).orElseThrow(() -> new ZoltConfigException(
                "Unsupported value for [" + section + "].distribution in "
                        + sourceName
                        + ": `"
                        + raw.orElseThrow()
                        + "`. Supported values are: "
                        + JavaDistribution.supportedIds()
                        + ".")));
    }

    private static Optional<ToolchainPolicy> optionalPolicy(TomlTable table, String sourceName) {
        Optional<String> raw = optionalString(table, "toolchain.java", "policy", sourceName);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ToolchainPolicy.fromId(raw.orElseThrow()).orElseThrow(() -> new ZoltConfigException(
                "Unsupported value for [toolchain.java].policy in "
                        + sourceName
                        + ": `"
                        + raw.orElseThrow()
                        + "`. Supported values are: "
                        + ToolchainPolicy.supportedIds()
                        + ".")));
    }

    private static Set<JavaFeature> features(TomlTable table, String section, String sourceName) {
        Object rawValue = table.get(List.of("features"));
        if (rawValue == null) {
            return Set.of();
        }
        if (!(rawValue instanceof TomlArray array)) {
            throw new ZoltConfigException(
                    "Invalid value for [" + section + "].features in " + sourceName + ". Use an array of strings.");
        }
        LinkedHashSet<JavaFeature> features = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            Object element = array.get(index);
            if (!(element instanceof String value) || value.isBlank()) {
                throw new ZoltConfigException(
                        "Invalid value for [" + section + "].features[" + index + "] in " + sourceName + ". Use a non-empty string.");
            }
            String normalized = value.strip();
            features.add(JavaFeature.fromId(normalized).orElseThrow(() -> new ZoltConfigException(
                    "Unsupported value for [" + section + "].features in "
                            + sourceName
                            + ": `"
                            + normalized
                            + "`. Supported values are: "
                            + JavaFeature.supportedIds()
                            + ".")));
        }
        return Collections.unmodifiableSet(features);
    }
}
