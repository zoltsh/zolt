package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for authored resource roots, filtering, and tokens. */
public final class FinalManifestResourceFields {
    public static final ManifestField RESOURCES_MAIN = field(
            FinalManifestPaths.RESOURCES, "main", ManifestValueKind.STRING_ARRAY, 6_201);
    public static final ManifestField RESOURCES_TEST = field(
            FinalManifestPaths.RESOURCES, "test", ManifestValueKind.STRING_ARRAY, 6_202);
    public static final ManifestField RESOURCES_FILTER_TARGETS = field(
            FinalManifestPaths.RESOURCES_FILTER, "targets", ManifestValueKind.STRING_ARRAY, 6_211);
    public static final ManifestField RESOURCES_FILTER_INCLUDE = field(
            FinalManifestPaths.RESOURCES_FILTER, "include", ManifestValueKind.STRING_ARRAY, 6_212);
    public static final ManifestField RESOURCES_FILTER_MISSING = field(
            FinalManifestPaths.RESOURCES_FILTER, "missing", ManifestValueKind.STRING, 6_213);
    public static final ManifestField RESOURCES_TOKENS_ENTRY = oneLineObjectField(
            FinalManifestPaths.RESOURCES_TOKENS,
            "<id>",
            ManifestValueKind.INLINE_TABLE,
            6_221,
            FinalManifestObjectShapes.RESOURCE_TOKEN);

    private FinalManifestResourceFields() {
    }

    static List<ManifestField> fields() {
        return List.of(
                RESOURCES_MAIN,
                RESOURCES_TEST,
                RESOURCES_FILTER_TARGETS,
                RESOURCES_FILTER_INCLUDE,
                RESOURCES_FILTER_MISSING,
                RESOURCES_TOKENS_ENTRY);
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.field(section, name, kind, canonicalOrder);
    }

    private static ManifestField oneLineObjectField(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder,
            ManifestObjectShape objectShape) {
        return FinalManifestFieldFactory.oneLineObjectField(
                section, name, kind, canonicalOrder, objectShape);
    }
}
