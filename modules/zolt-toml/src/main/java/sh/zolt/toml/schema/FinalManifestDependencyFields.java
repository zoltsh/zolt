package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for dependency lanes, constraints, and policy. */
public final class FinalManifestDependencyFields {
    public static final ManifestField DEPENDENCIES_ENTRY = mutableEntry(
            FinalManifestPaths.DEPENDENCIES, 5_001);
    public static final ManifestField DEPENDENCIES_API_ENTRY = mutableEntry(
            FinalManifestPaths.DEPENDENCIES_API, 5_011);
    public static final ManifestField DEPENDENCIES_RUNTIME_ENTRY = mutableEntry(
            FinalManifestPaths.DEPENDENCIES_RUNTIME, 5_021);
    public static final ManifestField DEPENDENCIES_PROVIDED_ENTRY = mutableEntry(
            FinalManifestPaths.DEPENDENCIES_PROVIDED, 5_031);
    public static final ManifestField DEPENDENCIES_DEV_ENTRY = mutableEntry(
            FinalManifestPaths.DEPENDENCIES_DEV, 5_041);
    public static final ManifestField DEPENDENCIES_TEST_ENTRY = mutableEntry(
            FinalManifestPaths.DEPENDENCIES_TEST, 5_051);
    public static final ManifestField DEPENDENCIES_PROCESSOR_ENTRY = mutableEntry(
            FinalManifestPaths.DEPENDENCIES_PROCESSOR, 5_061);
    public static final ManifestField DEPENDENCIES_TEST_PROCESSOR_ENTRY = mutableEntry(
            FinalManifestPaths.DEPENDENCIES_TEST_PROCESSOR, 5_071);
    public static final ManifestField DEPENDENCY_CONSTRAINTS_ENTRY = mutableEntry(
            FinalManifestPaths.DEPENDENCY_CONSTRAINTS, 5_081);
    public static final ManifestField DEPENDENCY_POLICY_CONFLICTS = field(
            FinalManifestPaths.DEPENDENCY_POLICY,
            "conflicts",
            ManifestValueKind.STRING,
            5_091);
    public static final ManifestField DEPENDENCY_POLICY_DENY = field(
            FinalManifestPaths.DEPENDENCY_POLICY,
            "deny",
            ManifestValueKind.INLINE_TABLE_ARRAY,
            5_092);
    public static final ManifestField DEPENDENCY_LICENSE_POLICY_ALLOW = field(
            FinalManifestPaths.DEPENDENCY_LICENSE_POLICY,
            "allow",
            ManifestValueKind.STRING_ARRAY,
            5_101);
    public static final ManifestField DEPENDENCY_LICENSE_POLICY_DENY = field(
            FinalManifestPaths.DEPENDENCY_LICENSE_POLICY,
            "deny",
            ManifestValueKind.STRING_ARRAY,
            5_102);
    public static final ManifestField DEPENDENCY_LICENSE_POLICY_UNKNOWN = field(
            FinalManifestPaths.DEPENDENCY_LICENSE_POLICY,
            "unknown",
            ManifestValueKind.STRING,
            5_103);
    public static final ManifestField DEPENDENCY_LICENSE_EXCEPTION_ALLOW = field(
            FinalManifestPaths.DEPENDENCY_LICENSE_EXCEPTION,
            "allow",
            ManifestValueKind.STRING_ARRAY,
            5_111);
    public static final ManifestField DEPENDENCY_LICENSE_EXCEPTION_VERSION = field(
            FinalManifestPaths.DEPENDENCY_LICENSE_EXCEPTION,
            "version",
            ManifestValueKind.STRING,
            5_112);
    public static final ManifestField DEPENDENCY_LICENSE_EXCEPTION_REASON = field(
            FinalManifestPaths.DEPENDENCY_LICENSE_EXCEPTION,
            "reason",
            ManifestValueKind.STRING,
            5_113);

    private FinalManifestDependencyFields() {
    }

    static List<ManifestField> fields() {
        return List.of(
                DEPENDENCIES_ENTRY,
                DEPENDENCIES_API_ENTRY,
                DEPENDENCIES_RUNTIME_ENTRY,
                DEPENDENCIES_PROVIDED_ENTRY,
                DEPENDENCIES_DEV_ENTRY,
                DEPENDENCIES_TEST_ENTRY,
                DEPENDENCIES_PROCESSOR_ENTRY,
                DEPENDENCIES_TEST_PROCESSOR_ENTRY,
                DEPENDENCY_CONSTRAINTS_ENTRY,
                DEPENDENCY_POLICY_CONFLICTS,
                DEPENDENCY_POLICY_DENY,
                DEPENDENCY_LICENSE_POLICY_ALLOW,
                DEPENDENCY_LICENSE_POLICY_DENY,
                DEPENDENCY_LICENSE_POLICY_UNKNOWN,
                DEPENDENCY_LICENSE_EXCEPTION_ALLOW,
                DEPENDENCY_LICENSE_EXCEPTION_VERSION,
                DEPENDENCY_LICENSE_EXCEPTION_REASON);
    }

    private static ManifestField mutableEntry(
            ManifestPath section,
            int canonicalOrder) {
        return FinalManifestFieldFactory.mutableMapEntry(
                section,
                "<coordinate>",
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                canonicalOrder);
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.field(section, name, kind, canonicalOrder);
    }
}
