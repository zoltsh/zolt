package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for Zolt and Java toolchain requests. */
public final class FinalManifestToolchainFields {
    public static final ManifestField ZOLT_VERSION = field(
            FinalManifestPaths.TOOLCHAIN_ZOLT, "version", ManifestValueKind.STRING, 3_010);
    public static final ManifestField JAVA_VERSION = field(
            FinalManifestPaths.TOOLCHAIN_JAVA, "version", ManifestValueKind.INTEGER, 3_110);
    public static final ManifestField JAVA_DISTRIBUTION = field(
            FinalManifestPaths.TOOLCHAIN_JAVA, "distribution", ManifestValueKind.STRING, 3_120);
    public static final ManifestField JAVA_FEATURES = field(
            FinalManifestPaths.TOOLCHAIN_JAVA, "features", ManifestValueKind.STRING_ARRAY, 3_130);
    public static final ManifestField JAVA_POLICY = field(
            FinalManifestPaths.TOOLCHAIN_JAVA, "policy", ManifestValueKind.STRING, 3_140);
    public static final ManifestField JAVA_TEST_VERSION = field(
            FinalManifestPaths.TOOLCHAIN_JAVA_TEST, "version", ManifestValueKind.INTEGER, 3_210);
    public static final ManifestField JAVA_TEST_DISTRIBUTION = field(
            FinalManifestPaths.TOOLCHAIN_JAVA_TEST,
            "distribution",
            ManifestValueKind.STRING,
            3_220);
    public static final ManifestField JAVA_TEST_POLICY = field(
            FinalManifestPaths.TOOLCHAIN_JAVA_TEST, "policy", ManifestValueKind.STRING, 3_230);

    private FinalManifestToolchainFields() {
    }

    static List<ManifestField> fields() {
        return List.of(
                ZOLT_VERSION,
                JAVA_VERSION,
                JAVA_DISTRIBUTION,
                JAVA_FEATURES,
                JAVA_POLICY,
                JAVA_TEST_VERSION,
                JAVA_TEST_DISTRIBUTION,
                JAVA_TEST_POLICY);
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.field(section, name, kind, canonicalOrder);
    }
}
