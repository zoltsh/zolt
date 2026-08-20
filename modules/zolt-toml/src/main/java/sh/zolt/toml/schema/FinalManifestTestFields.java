package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for authored test sources, runtime, integration, and suites. */
public final class FinalManifestTestFields {
    public static final ManifestField TEST_SOURCES_JAVA = field(
            FinalManifestPaths.TEST_SOURCES, "java", ManifestValueKind.STRING_ARRAY, 6_701);
    public static final ManifestField TEST_SOURCES_GROOVY = field(
            FinalManifestPaths.TEST_SOURCES, "groovy", ManifestValueKind.STRING_ARRAY, 6_702);
    public static final ManifestField TEST_RUNTIME_JVM_ARGS = field(
            FinalManifestPaths.TEST_RUNTIME, "jvmArgs", ManifestValueKind.STRING_ARRAY, 6_711);
    public static final ManifestField TEST_RUNTIME_PROPERTIES = field(
            FinalManifestPaths.TEST_RUNTIME, "properties", ManifestValueKind.INLINE_TABLE, 6_712);
    public static final ManifestField TEST_RUNTIME_ENV = field(
            FinalManifestPaths.TEST_RUNTIME, "env", ManifestValueKind.INLINE_TABLE, 6_713);
    public static final ManifestField TEST_RUNTIME_EVENTS = field(
            FinalManifestPaths.TEST_RUNTIME, "events", ManifestValueKind.STRING_ARRAY, 6_714);
    public static final ManifestField TEST_INTEGRATION_SOURCES = field(
            FinalManifestPaths.TEST_INTEGRATION, "sources", ManifestValueKind.STRING_ARRAY, 6_721);
    public static final ManifestField TEST_INTEGRATION_RESOURCES = field(
            FinalManifestPaths.TEST_INTEGRATION, "resources", ManifestValueKind.STRING_ARRAY, 6_722);
    public static final ManifestField TEST_SUITE_CLASSES = field(
            FinalManifestPaths.TEST_SUITE, "classes", ManifestValueKind.STRING_ARRAY, 6_731);
    public static final ManifestField TEST_SUITE_EXCLUDE_CLASSES = field(
            FinalManifestPaths.TEST_SUITE, "excludeClasses", ManifestValueKind.STRING_ARRAY, 6_732);
    public static final ManifestField TEST_SUITE_TAGS = field(
            FinalManifestPaths.TEST_SUITE, "tags", ManifestValueKind.STRING_ARRAY, 6_733);
    public static final ManifestField TEST_SUITE_EXCLUDE_TAGS = field(
            FinalManifestPaths.TEST_SUITE, "excludeTags", ManifestValueKind.STRING_ARRAY, 6_734);
    public static final ManifestField TEST_SUITE_WORKERS = field(
            FinalManifestPaths.TEST_SUITE, "workers", ManifestValueKind.INTEGER, 6_735);
    public static final ManifestField TEST_SUITE_LOCKS = field(
            FinalManifestPaths.TEST_SUITE, "locks", ManifestValueKind.INLINE_TABLE_ARRAY, 6_736);

    private FinalManifestTestFields() {
    }

    static List<ManifestField> fields() {
        return List.of(
                TEST_SOURCES_JAVA,
                TEST_SOURCES_GROOVY,
                TEST_RUNTIME_JVM_ARGS,
                TEST_RUNTIME_PROPERTIES,
                TEST_RUNTIME_ENV,
                TEST_RUNTIME_EVENTS,
                TEST_INTEGRATION_SOURCES,
                TEST_INTEGRATION_RESOURCES,
                TEST_SUITE_CLASSES,
                TEST_SUITE_EXCLUDE_CLASSES,
                TEST_SUITE_TAGS,
                TEST_SUITE_EXCLUDE_TAGS,
                TEST_SUITE_WORKERS,
                TEST_SUITE_LOCKS);
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.field(section, name, kind, canonicalOrder);
    }
}
